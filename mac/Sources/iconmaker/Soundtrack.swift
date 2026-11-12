import AVFoundation
import Foundation

/// Original background music for the store videos, synthesized here so there's nothing to license:
/// a soft pad (Cmaj7 – Am7 – Fmaj7 – G6), a plucked arpeggio, a quiet bass, light echo, plus optional
/// soft "whoosh" accents (scene changes) and "tap" clicks (button presses).
enum Soundtrack {
    static let rate = 44_100.0

    /// Chord tones as MIDI notes; each chord lasts one bar.
    private static let chords: [[Int]] = [[60, 64, 67, 71], [57, 60, 64, 67], [53, 57, 60, 64], [55, 59, 62, 64]]
    private static let bpm = 92.0

    private static func hz(_ midi: Int) -> Double { 440 * pow(2, Double(midi - 69) / 12) }

    static func render(seconds: Double, whooshes: [Double] = [], taps: [Double] = []) -> [Float] {
        let n = Int(seconds * rate)
        var out = [Float](repeating: 0, count: n)
        let beat = 60 / bpm, bar = beat * 4

        // Pad: detuned sines per chord tone, slow swell within each bar, crossfaded between bars.
        let bars = Int(ceil(seconds / bar)) + 1
        for b in 0..<bars {
            let chord = chords[b % chords.count], start = Double(b) * bar
            let from = max(0, Int((start - 0.6) * rate)), to = min(n, Int((start + bar + 0.6) * rate))
            guard from < to else { continue }
            for i in from..<to {
                let t = Double(i) / rate, local = t - start
                let env = smooth((local + 0.6) / 1.2) * smooth((bar + 0.6 - local) / 1.2)
                var s = 0.0
                for note in chord {
                    let f = hz(note)
                    s += sin(2 * .pi * f * t) + 0.6 * sin(2 * .pi * f * 1.003 * t) + 0.25 * sin(2 * .pi * f * 2 * t)
                }
                out[i] += Float(0.022 * env * s)
            }
        }

        // Plucked arpeggio (eighth notes, up the chord an octave higher) and a bass note on each bar.
        let pattern = [0, 1, 2, 3, 2, 1, 2, 3]
        var step = 0
        var t0 = 0.0
        while t0 < seconds {
            let chord = chords[Int(t0 / bar) % chords.count]
            pluck(&out, at: t0, freq: hz(chord[pattern[step % 8]] + 12), gain: 0.07, decay: 3.2)
            if step % 8 == 0 { pluck(&out, at: t0, freq: hz(chord[0] - 24), gain: 0.12, decay: 1.4) }
            step += 1; t0 += beat / 2
        }

        for w in whooshes { whoosh(&out, at: w) }
        for t in taps { tap(&out, at: t) }

        // Echo (dotted eighth), then fade in/out and a gentle limiter.
        let d = Int(beat * 0.75 * rate)
        if d < n { for i in d..<n { out[i] += 0.28 * out[i - d] } }
        for i in 0..<n {
            let t = Double(i) / rate
            let fade = smooth(t / 1.2) * smooth((seconds - t) / 2.0)
            out[i] = Float(tanh(Double(out[i]) * fade * 3.2) * 0.8)
        }
        return out
    }

    private static func smooth(_ x: Double) -> Double { let c = min(1, max(0, x)); return c * c * (3 - 2 * c) }

    private static func pluck(_ out: inout [Float], at t: Double, freq: Double, gain: Double, decay: Double) {
        let from = Int(t * rate), len = Int(2.5 * rate)
        for k in 0..<len where from + k < out.count {
            let x = Double(k) / rate
            let env = exp(-x * decay) * min(1, x * 200)
            out[from + k] += Float(gain * env * (sin(2 * .pi * freq * x) + 0.3 * sin(2 * .pi * freq * 2 * x)))
        }
    }

    /// Filtered noise swelling up into time `t`.
    private static func whoosh(_ out: inout [Float], at t: Double) {
        let len = Int(0.7 * rate), start = Int((t - 0.5) * rate)
        var rng = SystemRandomNumberGenerator(), lp = 0.0
        for k in 0..<len {
            let i = start + k
            guard i >= 0, i < out.count else { continue }
            let x = Double(k) / Double(len)
            lp += (Double.random(in: -1...1, using: &rng) - lp) * (0.02 + 0.2 * x)
            out[i] += Float(0.10 * lp * sin(.pi * x))
        }
    }

    /// A short, soft click like a UI tap.
    private static func tap(_ out: inout [Float], at t: Double) {
        let from = Int(t * rate), len = Int(0.08 * rate)
        for k in 0..<len where from + k >= 0 && from + k < out.count {
            let x = Double(k) / rate
            out[from + k] += Float(0.16 * exp(-x * 70) * sin(2 * .pi * 1_600 * x))
        }
    }

    /// Writes AAC (.m4a), which an mp4 can carry without re-encoding the video.
    static func write(_ samples: [Float], to url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2)!
        let file = try AVAudioFile(forWriting: url, settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 160_000,
        ])
        let chunk = 8_192
        var pos = 0
        while pos < samples.count {
            let count = min(chunk, samples.count - pos)
            let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(count))!
            buf.frameLength = AVAudioFrameCount(count)
            for c in 0..<2 { for k in 0..<count { buf.floatChannelData![c][k] = samples[pos + k] } }
            try file.write(from: buf)
            pos += count
        }
    }

    /// Puts `audio` under `video` (video copied as is), trimmed to the video's length.
    static func mux(video: URL, audio: URL, to out: URL) throws {
        try? FileManager.default.removeItem(at: out)
        let v = AVURLAsset(url: video), a = AVURLAsset(url: audio)
        let comp = AVMutableComposition()
        guard let vt = v.tracks(withMediaType: .video).first, let at = a.tracks(withMediaType: .audio).first else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let range = CMTimeRange(start: .zero, duration: v.duration)
        let cv = comp.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)!
        try cv.insertTimeRange(range, of: vt, at: .zero)
        cv.preferredTransform = vt.preferredTransform
        let ca = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)!
        try ca.insertTimeRange(CMTimeRange(start: .zero, duration: min(v.duration, a.duration)), of: at, at: .zero)
        guard let ex = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetPassthrough) else {
            throw CocoaError(.fileWriteUnknown)
        }
        ex.outputURL = out; ex.outputFileType = .mp4
        let done = DispatchSemaphore(value: 0)
        ex.exportAsynchronously { done.signal() }
        done.wait()
        if ex.status != .completed { throw ex.error ?? CocoaError(.fileWriteUnknown) }
    }
}
