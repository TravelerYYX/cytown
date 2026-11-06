import Foundation
import UIKit
import Vision
import ImageIO
import CoreMedia

@objc public class QrImageDecoder: NSObject {
    private static let worker = DispatchQueue(label: "io.ciyuantown.scan", qos: .userInitiated)
    private static let lock = NSLock()
    private static var generation = 0
    private static var active = false
    private static var processing = false

    @objc public static func startCamera() {
        lock.lock(); generation += 1; active = true; processing = false; lock.unlock()
    }

    @objc public static func stopCamera() {
        lock.lock(); generation += 1; active = false; processing = false; lock.unlock()
    }

    @objc public static func frame(_ buffer: CMSampleBuffer, completion: @escaping (String) -> Void) {
        lock.lock()
        guard active && !processing else { lock.unlock(); return }
        processing = true
        let token = generation
        lock.unlock()
        worker.async {
            let request = VNDetectBarcodesRequest()
            request.symbologies = [.qr]
            try? VNImageRequestHandler(cmSampleBuffer: buffer).perform([request])
            let value = request.results?.compactMap { $0.payloadStringValue }.first ?? ""
            lock.lock()
            let current = active && token == generation
            if token == generation { processing = false }
            lock.unlock()
            if current && !value.isEmpty {
                DispatchQueue.main.async {
                    lock.lock(); let deliver = active && token == generation; lock.unlock()
                    if deliver { completion(value) }
                }
            }
        }
    }
    @objc public static func decode(_ path: String, completion: @escaping (String, String) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let filePath = path.hasPrefix("file:") ? (URL(string: path)?.path ?? path) : path
            guard let image = UIImage(contentsOfFile: filePath), let cgImage = image.cgImage else {
                DispatchQueue.main.async { completion("", "图片无法读取，请重新选择") }
                return
            }
            let orientation: CGImagePropertyOrientation
            switch image.imageOrientation {
            case .up: orientation = .up
            case .down: orientation = .down
            case .left: orientation = .left
            case .right: orientation = .right
            case .upMirrored: orientation = .upMirrored
            case .downMirrored: orientation = .downMirrored
            case .leftMirrored: orientation = .leftMirrored
            case .rightMirrored: orientation = .rightMirrored
            @unknown default: orientation = .up
            }
            let request = VNDetectBarcodesRequest()
            request.symbologies = [.qr]
            do {
                try VNImageRequestHandler(cgImage: cgImage, orientation: orientation).perform([request])
                let value = request.results?.compactMap { $0.payloadStringValue }.first ?? ""
                DispatchQueue.main.async { completion(value, "") }
            } catch {
                DispatchQueue.main.async { completion("", "图片识别失败，请重新选择") }
            }
        }
    }
}
