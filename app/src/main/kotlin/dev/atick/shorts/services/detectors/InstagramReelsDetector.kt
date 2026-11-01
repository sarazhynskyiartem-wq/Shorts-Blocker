/*
 * Copyright 2025 Atick Faisal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.atick.shorts.services.detectors

import android.content.res.Resources
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Detector for Instagram Reels short-form content.
 *
 * This detector identifies when a user is actively watching Instagram Reels and,
 * crucially, avoids false positives on Direct messages, Stories and Settings —
 * screens the previous implementation wrongly treated as Reels.
 *
 * **Detection Strategy:**
 * 1. **Early exit for non-Reels screens**: if a Stories viewer (`reel_viewer`) or a
 *    Direct/inbox screen (`direct_`, `thread_`) node is seen, immediately return false.
 * 2. **Reels tab detection**: if the `clips_tab` node exists and is selected, it's Reels.
 * 3. **Fullscreen Reels detection**: a positive `clips_viewer`/`clips_video` signal
 *    combined with the absence of the bottom navigation (`feed_tab`) indicates Reels
 *    opened from the feed, Explore or a profile.
 *
 * The key fix over the old logic: Reels is only reported on a *positive* Reels signal.
 * The mere absence of `feed_tab` is no longer treated as Reels, because Direct, Stories
 * and Settings also lack it.
 *
 * **Implementation Details:**
 * - Performs a breadth-first scan of up to 300 nodes. The previous 10-node scan only
 *   reached the bottom navigation, which is why "no feed_tab" became a (wrong) proxy
 *   for Reels.
 * - Instagram reuses `clips_viewer` widely, so detection relies on combined signals
 *   rather than any single node.
 *
 * **Known Limitations:**
 * - Detection patterns may break after major Instagram redesigns.
 * - The 300-node cap may miss Reels signals in unusually deep layouts.
 *
 * **Compatibility:**
 * - Tested with Instagram app versions 300.x - 320.x
 * - May require updates for major Instagram redesigns
 */
class InstagramReelsDetector : ShortFormContentDetector {

    override fun getPackageName(): String = "com.instagram.android"

    override fun isShortFormContent(
        event: AccessibilityEvent,
        rootNode: AccessibilityNodeInfo,
        resources: Resources,
    ): Boolean {
        // Instagram uses clips_viewer for everything, so we need to be very specific
        // Check if we're in the Reels tab/activity specifically

        val className = event.className?.toString()
        Timber.v("[Instagram] Event className: $className")

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(rootNode)
        var nodesScanned = 0
        var feedTabFound = false
        var clipsViewerFound = false

        // Deeper scan: 10 nodes only covered the bottom nav, which is why
        // "no feed_tab" was used as a (wrong) proxy for Reels.
        while (stack.isNotEmpty() && nodesScanned < 300) {
            val n = stack.removeFirst()
            nodesScanned++
            val id = n.viewIdResourceName

            if (id != null) {
                // Stories ("reel_viewer" in Instagram's naming) and Direct: never block.
                if ("reel_viewer" in id || "direct_" in id || "thread_" in id) {
                    Timber.v("[Instagram] Stories/Direct screen, skipping ($id)")
                    return false
                }
                if ("clips_tab" in id && n.isSelected) {
                    Timber.i("[Instagram] ✓ Reels tab selected")
                    return true
                }
                if ("feed_tab" in id) feedTabFound = true
                if ("clips_viewer" in id || "clips_video" in id) clipsViewerFound = true
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }

        // Reels opened from feed/Explore/profile: a positive Reels signal is required,
        // not just the absence of the bottom navigation.
        if (clipsViewerFound && !feedTabFound) {
            Timber.i("[Instagram] ✓ Fullscreen Reels viewer")
            return true
        }
        Timber.v("[Instagram] No Reels ($nodesScanned nodes, feedTab=$feedTabFound, clips=$clipsViewerFound)")
        return false
    }
}
