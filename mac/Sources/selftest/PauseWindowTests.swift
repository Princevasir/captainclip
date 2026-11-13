import Foundation
import ClipBridgeCore

func pauseWindowTests() {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    check(PauseWindow.until(.fifteenMinutes, now: t0) == t0.addingTimeInterval(900), "15 minute pause")
    check(PauseWindow.until(.oneHour, now: t0) == t0.addingTimeInterval(3600), "1 hour pause")
    check(PauseWindow.until(.untilResumed, now: t0) == nil, "until resumed has no end")
    check(PauseChoice.allCases.map(\.title) == ["15 minutes", "1 hour", "Until I resume"], "pause choice titles")
    check(PauseWindow.isActive(manual: false, until: t0.addingTimeInterval(60), now: t0), "timed pause active before its end")
    check(!PauseWindow.isActive(manual: false, until: t0, now: t0.addingTimeInterval(3600)), "pause window uses wall clock")
    check(!PauseWindow.isActive(manual: false, until: nil, now: t0), "no pause when nothing set")
    check(PauseWindow.isActive(manual: true, until: nil, now: t0), "manual pause is active")
    check(MenuText.pausedUntil(t0.addingTimeInterval(60), now: t0, time: { _ in "3:45 PM" }) == "Paused until 3:45 PM", "paused-until line")
    check(MenuText.pausedUntil(t0, now: t0.addingTimeInterval(1), time: { _ in "3:45 PM" }) == nil, "no paused-until line after the deadline")
}
