import Foundation

/// A one-time code is cleared from the pasteboard only if nothing newer was copied since it was written.
public enum ClearAfter {
    public static func shouldClear(writtenChangeCount: Int, currentChangeCount: Int) -> Bool {
        writtenChangeCount == currentChangeCount
    }
}
