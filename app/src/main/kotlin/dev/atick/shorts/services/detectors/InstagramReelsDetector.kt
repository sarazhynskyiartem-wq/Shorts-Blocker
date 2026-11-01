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
 * Detection keys on the Reels **player's own view ids** rather than on the entry
 * point, so a Reel is blocked wherever it is opened from (the Reels tab, Explore,
 * or a Direct message) while Direct chats, the home feed, the Explore grid, Stories
 * and Settings are left alone.
 *
 * **Detection strategy (first match wins):**
 * 1. **Stories** — if a Stories viewer (`reel_viewer`, Instagram's internal name for
 *    Stories) is present, never block.
 * 2. **Reels tab** — if the bottom-navigation `clips_tab` is present and selected.
 * 3. **Fullscreen Reels player** — if the clips viewer (`clips_viewer_view_pager`,
 *    `clips_viewer_*`) is present *and* the bottom navigation (`feed_tab`) is absent.
 *    The fullscreen player (Direct reel, Explore reel, immersive Reels) runs without
 *    the bottom bar, whereas the home feed, Direct chat and Explore grid keep it — so
 *    requiring `feed_tab` to be absent avoids false positives there.
 *
 * The clips viewer is also cached off-screen inside the main activity's view pager;
 * in that case `feed_tab` is still present, so rule 3 correctly does not fire.
 *
 * **Key player ids (Instagram 2025):** `clips_viewer_view_pager`, `clips_video_container`,
 * `clips_ufi_component`, `like_button` / `comment_button` / `save_button`,
 * `inline_follow_button`; bottom nav: `feed_tab`, `clips_tab`, `direct_tab`,
 * `search_tab`, `profile_tab`.
 *
 * **Known Limitations:**
 * - Keyed on Instagram view ids; may need updates after a major Instagram redesign.
 * - The Explore grid itself (thumbnail browsing) is intentionally not blocked.
 */
class InstagramReelsDetector : ShortFormContentDetector {

    override fun getPackageName(): String = "com.instagram.android"

    override fun isShortFormContent(
        event: AccessibilityEvent,
        rootNode: AccessibilityNodeInfo,
        resources: Resources,
    ): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(rootNode)
        var nodesScanned = 0
        var clipsTabSelected = false
        var hasClipsViewer = false
        var hasFeedTab = false
        var hasStoryViewer = false

        while (stack.isNotEmpty() && nodesScanned < 400) {
            val n = stack.removeFirst()
            nodesScanned++
            val id = n.viewIdResourceName
            if (id != null) {
                when {
                    "reel_viewer" in id -> hasStoryViewer = true
                    "clips_tab" in id && n.isSelected -> clipsTabSelected = true
                    "clips_viewer" in id -> hasClipsViewer = true
                    "feed_tab" in id -> hasFeedTab = true
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }

        // Stories use the "reel_viewer" hierarchy and are never blocked.
        if (hasStoryViewer) {
            Timber.v("[Instagram] Stories viewer, skipping")
            return false
        }
        // Dedicated Reels tab (bottom-nav Reels selected).
        if (clipsTabSelected) {
            Timber.i("[Instagram] ✓ Reels tab selected")
            return true
        }
        // Fullscreen Reels player (Direct/Explore/immersive): clips viewer present and
        // the bottom navigation is gone.
        if (hasClipsViewer && !hasFeedTab) {
            Timber.i("[Instagram] ✓ Fullscreen Reels player")
            return true
        }
        Timber.v(
            "[Instagram] No Reels ($nodesScanned nodes, clipsViewer=$hasClipsViewer, " +
                "feedTab=$hasFeedTab)",
        )
        return false
    }
}
