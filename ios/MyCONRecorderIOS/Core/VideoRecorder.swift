import Foundation
import AVFoundation
import CoreVideo

final class VideoRecorder {
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var firstTimestamp: TimeInterval?
    private let outputURL: URL
    private let queue = DispatchQueue(label: "ir.mycon.ios.video")

    init(outputURL: URL) {
        self.outputURL = outputURL
        try? FileManager.default.removeItem(at: outputURL)
    }

    func append(pixelBuffer: CVPixelBuffer, timestamp: TimeInterval) {
        queue.async {
            do {
                if self.writer == nil {
                    try self.configure(pixelBuffer: pixelBuffer)
                    self.firstTimestamp = timestamp
                }
                guard let writer = self.writer,
                      let input = self.input,
                      let adaptor = self.adaptor,
                      writer.status == .writing,
                      input.isReadyForMoreMediaData,
                      let start = self.firstTimestamp else { return }
                let pts = CMTime(seconds: max(0, timestamp - start), preferredTimescale: 1_000_000)
                adaptor.append(pixelBuffer, withPresentationTime: pts)
            } catch {
                print("MyCON video writer error:", error)
            }
        }
    }

    func stop(completion: @escaping () -> Void) {
        queue.async {
            guard let writer = self.writer, let input = self.input else {
                completion(); return
            }
            input.markAsFinished()
            writer.finishWriting {
                completion()
            }
        }
    }

    private func configure(pixelBuffer: CVPixelBuffer) throws {
        let w = CVPixelBufferGetWidth(pixelBuffer)
        let h = CVPixelBufferGetHeight(pixelBuffer)
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: w,
            AVVideoHeightKey: h,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: max(8_000_000, w * h * 5),
                AVVideoExpectedSourceFrameRateKey: 30,
                AVVideoMaxKeyFrameIntervalKey: 30
            ]
        ]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = true
        guard writer.canAdd(input) else { throw NSError(domain: "MyCONVideo", code: 1) }
        writer.add(input)
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: nil)
        guard writer.startWriting() else { throw writer.error ?? NSError(domain: "MyCONVideo", code: 2) }
        writer.startSession(atSourceTime: .zero)
        self.writer = writer
        self.input = input
        self.adaptor = adaptor
    }
}
