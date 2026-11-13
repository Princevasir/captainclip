import Foundation

/// How long syncing stays paused.
public enum PauseChoice: CaseIterable {
    case fifteenMinutes, oneHour, untilResumed

    public var title: String {
        switch self {
        case .fifteenMinutes: return "15 minutes"
        case .oneHour: return "1 hour"
        case .untilResumed: return "Until I resume"
        }
    }
}

/// Pause deadlines are wall-clock times, so a sleeping Mac wakes up already resumed.
public struct PauseWindow {
    /// When the pause ends; nil = never (until resumed).
    public static func until(_ c: PauseChoice, now: Date) -> Date? {
        switch c {
        case .fifteenMinutes: return now.addingTimeInterval(15 * 60)
        case .oneHour: return now.addingTimeInterval(60 * 60)
        case .untilResumed: return nil
        }
    }

    public static func isActive(manual: Bool, until: Date?, now: Date) -> Bool {
        if manual { return true }
        guard let until else { return false }
        return now < until
    }
}
