import CoreBluetooth
import Foundation
import MurmurCore

/// Murmur over Bluetooth LE: the same GATT service, characteristic, fragment format and connection
/// rules as the Android app (`BleTransport.kt`), so iPhones and Android phones form one mesh.
///
/// Every iPhone is both a GATT server (advertising the Murmur service; neighbours connect and subscribe)
/// and a GATT client (scanning and connecting out). All work happens on `queue`, which the mesh node
/// shares, so packet order is preserved and nothing needs locking.
final class BleTransport: NSObject, LinkTransport {
    let queue: DispatchQueue
    private let clock: Clock
    private let myPeerId: PeerId
    private let log: (String) -> Void

    // Hooks into the mesh (called on `queue`).
    var onLinkUp: ((MeshLink) -> Void)?
    var onReceived: ((String, [UInt8]) -> Void)?
    var onLinkDown: ((String) -> Void)?
    var onStatus: ((BleStatus) -> Void)?

    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var characteristic: CBMutableCharacteristic?
    private var serviceAdded = false
    private var running = false
    private var housekeeping: DispatchSourceTimer?
    private var status = BleStatus() {
        didSet { if status != oldValue { onStatus?(status) } }
    }

    private let serviceUUID = CBUUID(string: Murmur.serviceUUID)
    private let characteristicUUID = CBUUID(string: Murmur.characteristicUUID)

    /// A device seen while scanning.
    private final class Candidate {
        let peripheral: CBPeripheral
        var peerId: PeerId?
        var rssi: Double = -100
        var lastSeen: Int64
        var waitStart: Int64
        init(_ peripheral: CBPeripheral, now: Int64) {
            self.peripheral = peripheral
            lastSeen = now
            waitStart = now
        }
    }

    private var candidates: [UUID: Candidate] = [:]
    private var clients: [UUID: ClientLink] = [:]
    private var servers: [UUID: ServerLink] = [:]
    private var failures: [UUID: Int] = [:]
    private var retryAt: [UUID: Int64] = [:]
    private var linkCounter = 0

    init(queue: DispatchQueue, clock: Clock, myPeerId: PeerId, log: @escaping (String) -> Void) {
        self.queue = queue
        self.clock = clock
        self.myPeerId = myPeerId
        self.log = log
        super.init()
    }

    // MARK: lifecycle (call on `queue`)

    func start() {
        guard !running else { return }
        running = true
        // Creating the managers shows the Bluetooth permission prompt the first time.
        central = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: true])
        peripheralManager = CBPeripheralManager(delegate: self, queue: queue, options: nil)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
        housekeeping = timer
        log("BLE start")
    }

    func stop() {
        guard running else { return }
        running = false
        housekeeping?.cancel()
        housekeeping = nil
        for link in Array(clients.values) { closeClient(link, reason: "stopping", retry: false) }
        for link in Array(servers.values) { closeServer(link, reason: "stopping") }
        if central?.state == .poweredOn { central.stopScan() }
        if peripheralManager?.state == .poweredOn {
            peripheralManager.stopAdvertising()
            peripheralManager.removeAllServices()
        }
        serviceAdded = false
        characteristic = nil
        candidates.removeAll()
        status.scanning = false
        status.advertising = false
        publish()
        log("BLE stop")
    }

    // MARK: links

    /// One GATT connection, either direction.
    class BleLink: MeshLink {
        let id: String
        let serial: Int
        unowned let transport: BleTransport
        var ready = false
        var closed = false
        var peerId: PeerId?
        let reassembler: Reassembler
        var streamCounter = UInt32.random(in: 0...UInt32.max)
        var pending: [[UInt8]] = []
        var queuedPackets = 0
        /// Fragments left in the packet currently at the head of `pending`, for packet accounting.
        var fragmentsLeftInPacket: [Int] = []
        var isClient: Bool { false }

        init(id: String, serial: Int, transport: BleTransport, clock: Clock) {
            self.id = id
            self.serial = serial
            self.transport = transport
            reassembler = Reassembler(clock: clock)
        }

        /// Packet payload room per fragment.
        var chunkSize: Int { Fragmenter.minChunkSize }

        func send(_ packet: [UInt8]) -> Bool {
            if closed { return false }
            if !ready { return true }
            if queuedPackets >= BleTransport.maxQueuedPackets {
                transport.log("\(id): send queue full, dropping a packet")
                return true
            }
            let fragments = Fragmenter.fragment(packet, chunkSize: chunkSize, streamId: streamCounter)
            streamCounter &+= 1
            pending.append(contentsOf: fragments)
            fragmentsLeftInPacket.append(fragments.count)
            queuedPackets += 1
            // Never call back into the mesh synchronously: pump on the next turn of the queue.
            transport.queue.async { [weak self] in self?.pump() }
            return true
        }

        func onPeerIdentified(_ peerId: PeerId) {
            transport.queue.async { [weak self] in
                guard let self else { return }
                self.transport.onIdentified(self, peerId)
            }
        }

        /// One fragment went out.
        func fragmentSent() {
            guard !pending.isEmpty else { return }
            pending.removeFirst()
            if !fragmentsLeftInPacket.isEmpty {
                fragmentsLeftInPacket[0] -= 1
                if fragmentsLeftInPacket[0] <= 0 {
                    fragmentsLeftInPacket.removeFirst()
                    queuedPackets -= 1
                }
            }
        }

        func onBytes(_ bytes: [UInt8]) {
            if closed || !ready { return }
            if let packet = reassembler.accept(bytes) { transport.onReceived?(id, packet) }
        }

        func pump() {}
    }

    /// I connected out to their GATT server; I write, they notify.
    final class ClientLink: BleLink {
        let peripheral: CBPeripheral
        var remoteCharacteristic: CBCharacteristic?
        var connectedAt: Int64 = 0
        var startedAt: Int64
        var writeInFlightSince: Int64?
        override var isClient: Bool { true }

        init(peripheral: CBPeripheral, serial: Int, transport: BleTransport, clock: Clock) {
            self.peripheral = peripheral
            startedAt = clock.now()
            super.init(id: "c\(serial)-\(peripheral.identifier.uuidString.prefix(5))", serial: serial, transport: transport, clock: clock)
        }

        override var chunkSize: Int {
            // Android's server takes plain writes only (no "long" prepared writes), so stay within one ATT
            // packet: that's the without-response size, whatever iOS allows for with-response writes.
            Fragmenter.chunkSizeForMtu(peripheral.maximumWriteValueLength(for: .withoutResponse) + 3)
        }

        override func pump() {
            guard ready, !closed, writeInFlightSince == nil, let ch = remoteCharacteristic, let next = pending.first else { return }
            writeInFlightSince = transport.clock.now()
            peripheral.writeValue(Data(next), for: ch, type: .withResponse)
        }
    }

    /// They connected to my GATT server and subscribed; they write, I notify.
    final class ServerLink: BleLink {
        let central: CBCentral

        init(central: CBCentral, serial: Int, transport: BleTransport, clock: Clock) {
            self.central = central
            super.init(id: "s\(serial)-\(central.identifier.uuidString.prefix(5))", serial: serial, transport: transport, clock: clock)
        }

        override var chunkSize: Int { Fragmenter.chunkSizeForMtu(central.maximumUpdateValueLength + 3) }

        override func pump() {
            guard ready, !closed, let pm = transport.peripheralManager, let ch = transport.characteristic else { return }
            while let next = pending.first {
                // False = the transmit queue is full; peripheralManagerIsReady(toUpdateSubscribers:) resumes.
                guard pm.updateValue(Data(next), for: ch, onSubscribedCentrals: [central]) else { return }
                fragmentSent()
            }
        }
    }

    private func allLinks() -> [BleLink] { clients.values.map { $0 as BleLink } + servers.values.map { $0 as BleLink } }

    /// Link dedupe once the mesh knows who is on a link: keep the one whose GATT client is the lower
    /// peerId, exactly like Android, so both phones close the same duplicate.
    fileprivate func onIdentified(_ link: BleLink, _ peerId: PeerId) {
        link.peerId = peerId
        if link.closed { return }
        if let c = link as? ClientLink { candidates[c.peripheral.identifier]?.peerId = peerId }
        let duplicates = allLinks().filter { $0 !== link && !$0.closed && $0.ready && $0.peerId == peerId }
        for other in duplicates {
            let keep = preferred(link, other, peerId)
            let drop = keep === link ? other : link
            log("duplicate link to \(peerId): keeping \(keep.id), closing \(drop.id)")
            if let c = drop as? ClientLink { closeClient(c, reason: "duplicate", retry: false) }
            if let s = drop as? ServerLink { closeServer(s, reason: "duplicate") }
            if drop === link { break }
        }
        publish()
    }

    private func preferred(_ a: BleLink, _ b: BleLink, _ peerId: PeerId) -> BleLink {
        func clientOf(_ l: BleLink) -> PeerId { l.isClient ? myPeerId : peerId }
        let lower = min(myPeerId, peerId)
        let aOk = clientOf(a) == lower
        let bOk = clientOf(b) == lower
        if aOk && !bOk { return a }
        if bOk && !aOk { return b }
        return a.serial <= b.serial ? a : b
    }

    // MARK: connection policy

    private func tick() {
        guard running else { return }
        let now = clock.now()
        candidates = candidates.filter { now - $0.value.lastSeen <= BleTransport.candidateTtl }
        retryAt = retryAt.filter { $0.value > now }
        for link in Array(clients.values) where !link.closed {
            if !link.ready && now - link.startedAt > BleTransport.setupTimeout {
                closeClient(link, reason: "setup timed out", retry: true)
            } else if let since = link.writeInFlightSince, now - since > BleTransport.opTimeout {
                closeClient(link, reason: "write timed out", retry: true)
            }
        }
        for link in allLinks() { link.reassembler.purgeExpired() }
        evaluateConnections(now)
        publish()
    }

    private func evaluateConnections(_ now: Int64) {
        guard central?.state == .poweredOn else { return }
        let outbound = clients.values.filter { !$0.closed }.count
        guard outbound < BleTransport.maxOutbound else { return }
        let linkedPeers = Set(allLinks().filter { $0.ready && !$0.closed }.compactMap(\.peerId))
        let eligible = candidates.values.filter { c in
            now - c.lastSeen < 30_000 &&
                clients[c.peripheral.identifier] == nil &&
                (retryAt[c.peripheral.identifier] ?? 0) <= now &&
                !(c.peerId.map { linkedPeers.contains($0) } ?? false) &&
                shouldInitiate(c, now)
        }.sorted { $0.rssi > $1.rssi }
        for c in eligible.prefix(BleTransport.maxOutbound - outbound) { connect(c) }
    }

    /// The lower peerId connects. The higher one waits 8 s for the inbound connection, then connects
    /// anyway. Unknown peerId (no scan response) → connect after a short grace.
    private func shouldInitiate(_ c: Candidate, _ now: Int64) -> Bool {
        guard let theirs = c.peerId else { return now - c.waitStart >= BleTransport.peerIdGrace }
        return myPeerId < theirs || now - c.waitStart >= BleTransport.waitForInbound
    }

    private func connect(_ c: Candidate) {
        linkCounter += 1
        let link = ClientLink(peripheral: c.peripheral, serial: linkCounter, transport: self, clock: clock)
        link.peerId = c.peerId
        clients[c.peripheral.identifier] = link
        c.peripheral.delegate = self
        log("\(link.id): connecting (peer \(c.peerId.map { "\($0)" } ?? "?"))")
        central.connect(c.peripheral, options: nil)
    }

    private func closeClient(_ link: ClientLink, reason: String, retry: Bool) {
        if link.closed { return }
        link.closed = true
        link.pending.removeAll()
        link.reassembler.clear()
        if central?.state == .poweredOn { central.cancelPeripheralConnection(link.peripheral) }
        if clients[link.peripheral.identifier] === link { clients.removeValue(forKey: link.peripheral.identifier) }
        let wasReady = link.ready
        link.ready = false
        if wasReady { onLinkDown?(link.id) }
        log("\(link.id): closed (\(reason))")
        candidates[link.peripheral.identifier]?.waitStart = clock.now()
        if retry && running { recordFailure(link.peripheral.identifier) }
        publish()
    }

    private func closeServer(_ link: ServerLink, reason: String) {
        if link.closed { return }
        link.closed = true
        link.pending.removeAll()
        link.reassembler.clear()
        let wasReady = link.ready
        link.ready = false
        if wasReady { onLinkDown?(link.id) }
        // iOS can't hang up on a central; once it unsubscribes or disconnects the entry goes away.
        log("\(link.id): closed (\(reason))")
        publish()
    }

    private func recordFailure(_ id: UUID) {
        let n = (failures[id] ?? 0) + 1
        failures[id] = n
        // 2 s, 4 s, 8 s… then a minute's rest after repeated failures.
        let delay: Int64 = n > BleTransport.maxRetries ? 60_000 : Int64(1_000 << n)
        retryAt[id] = clock.now() + delay
        if n > BleTransport.maxRetries { failures[id] = 0 }
    }

    // MARK: radio

    private func startScanning() {
        guard central?.state == .poweredOn, !central.isScanning else { return }
        central.scanForPeripherals(withServices: [serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        status.scanning = true
        log("scanning")
    }

    private func openServer() {
        guard peripheralManager?.state == .poweredOn, !serviceAdded else { return }
        let ch = CBMutableCharacteristic(type: characteristicUUID, properties: [.write, .notify], value: nil, permissions: [.writeable])
        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [ch]
        characteristic = ch
        serviceAdded = true
        peripheralManager.add(service)
    }

    private func startAdvertising() {
        guard peripheralManager?.state == .poweredOn, !peripheralManager.isAdvertising else { return }
        // iOS can only advertise service UUIDs and a name, not Android's peerId service data; Android
        // connects to such advertisers after a short grace and learns the peerId from the first ANNOUNCE.
        peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    private func radio(from state: CBManagerState) -> BleStatus.Radio {
        switch state {
        case .poweredOn: return .poweredOn
        case .poweredOff: return .poweredOff
        case .unauthorized: return .unauthorized
        case .unsupported: return .unsupported
        case .resetting: return .resetting
        default: return .unknown
        }
    }

    private func publish() {
        status.links = allLinks().filter { $0.ready && !$0.closed }.count
        status.candidates = candidates.count
    }

    static let maxOutbound = 6
    static let maxRetries = 3
    static let opTimeout: Int64 = 5_000
    static let setupTimeout: Int64 = 25_000
    static let waitForInbound: Int64 = 8_000
    static let peerIdGrace: Int64 = 2_000
    static let candidateTtl: Int64 = 60_000
    static let maxQueuedPackets = 64
    static let rssiAlpha = 0.3
}

// MARK: - client role

extension BleTransport: CBCentralManagerDelegate, CBPeripheralDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        status.radio = radio(from: central.state)
        log("central state \(central.state.rawValue)")
        if central.state == .poweredOn {
            if running { startScanning() }
        } else {
            status.scanning = false
            for link in Array(clients.values) { closeClient(link, reason: "Bluetooth off", retry: false) }
            candidates.removeAll()
        }
        publish()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard running else { return }
        let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data]
        let peerId = serviceData?[serviceUUID].flatMap { $0.count >= PeerId.size ? PeerId.fromBytes(Array($0)) : nil }
        if peerId == myPeerId { return }
        let now = clock.now()
        let id = peripheral.identifier
        let c: Candidate
        if let existing = candidates[id] {
            c = existing
        } else {
            c = Candidate(peripheral, now: now)
            candidates[id] = c
            log("found \(id.uuidString.prefix(5)) peer=\(peerId.map { "\($0)" } ?? "?") rssi=\(RSSI)")
        }
        if let peerId { c.peerId = peerId }
        let rssi = RSSI.doubleValue
        if rssi < 0 { c.rssi = BleTransport.rssiAlpha * rssi + (1 - BleTransport.rssiAlpha) * c.rssi }
        c.lastSeen = now
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let link = clients[peripheral.identifier], !link.closed else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        link.connectedAt = clock.now()
        log("\(link.id): connected")
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard let link = clients[peripheral.identifier] else { return }
        closeClient(link, reason: "connect failed: \(error?.localizedDescription ?? "?")", retry: true)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard let link = clients[peripheral.identifier] else { return }
        closeClient(link, reason: "disconnected \(error?.localizedDescription ?? "")", retry: true)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let link = clients[peripheral.identifier], !link.closed else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            closeClient(link, reason: "Murmur service not found", retry: true)
            return
        }
        peripheral.discoverCharacteristics([characteristicUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let link = clients[peripheral.identifier], !link.closed else { return }
        guard error == nil, let ch = service.characteristics?.first(where: { $0.uuid == characteristicUUID }) else {
            closeClient(link, reason: "Murmur characteristic not found", retry: true)
            return
        }
        link.remoteCharacteristic = ch
        // Subscribing writes the CCCD: that's what tells the other side (Android or iPhone) the link is up.
        peripheral.setNotifyValue(true, for: ch)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard let link = clients[peripheral.identifier], !link.closed, !link.ready else { return }
        guard error == nil, characteristic.isNotifying else {
            closeClient(link, reason: "cannot enable notifications", retry: true)
            return
        }
        link.ready = true
        failures.removeValue(forKey: peripheral.identifier)
        retryAt.removeValue(forKey: peripheral.identifier)
        log("\(link.id): ready (client, chunk \(link.chunkSize))")
        onLinkUp?(link)
        link.pump()
        publish()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let link = clients[peripheral.identifier], let value = characteristic.value else { return }
        link.onBytes(Array(value))
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let link = clients[peripheral.identifier], !link.closed else { return }
        link.writeInFlightSince = nil
        if let error { log("\(link.id): write error \(error.localizedDescription)") }
        link.fragmentSent()
        link.pump()
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard let link = clients[peripheral.identifier] else { return }
        if invalidatedServices.contains(where: { $0.uuid == serviceUUID }) {
            closeClient(link, reason: "service went away", retry: true)
        }
    }
}

// MARK: - server role

extension BleTransport: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        log("peripheral state \(peripheral.state.rawValue)")
        if peripheral.state == .poweredOn {
            if running { openServer() }
        } else {
            serviceAdded = false
            characteristic = nil
            status.advertising = false
            for link in Array(servers.values) { closeServer(link, reason: "Bluetooth off") }
            servers.removeAll()
        }
        publish()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            status.lastError = "GATT server: \(error.localizedDescription)"
            serviceAdded = false
            return
        }
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        status.advertising = error == nil
        if let error { status.lastError = "advertising: \(error.localizedDescription)" } else { log("advertising") }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard running else { return }
        if let existing = servers[central.identifier], !existing.closed, existing.ready { return }
        linkCounter += 1
        let link = ServerLink(central: central, serial: linkCounter, transport: self, clock: clock)
        servers[central.identifier] = link
        link.ready = true
        log("\(link.id): ready (server, chunk \(link.chunkSize))")
        onLinkUp?(link)
        publish()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard let link = servers.removeValue(forKey: central.identifier) else { return }
        closeServer(link, reason: "unsubscribed")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        for request in requests {
            guard request.characteristic.uuid == characteristicUUID, request.offset == 0, request.value != nil else {
                peripheral.respond(to: first, withResult: .requestNotSupported)
                return
            }
        }
        peripheral.respond(to: first, withResult: .success)
        for request in requests {
            // Writes from a central that hasn't subscribed yet (or a closed duplicate) are ignored, as on Android.
            servers[request.central.identifier]?.onBytes(Array(request.value ?? Data()))
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        request.value = Data()
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        for link in servers.values { link.pump() }
    }
}
