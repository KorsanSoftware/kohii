/*
 * Copyright (c) 2019 Nam Nguyen, nam@ene.im
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kohii.core

import androidx.annotation.CallSuper
import kohii.core.Master.Companion.NO_TAG
import kohii.core.Master.MemoryMode
import kohii.core.Master.MemoryMode.AUTO
import kohii.core.Master.MemoryMode.BALANCED
import kohii.core.Master.MemoryMode.HIGH
import kohii.core.Master.MemoryMode.INFINITE
import kohii.core.Master.MemoryMode.LOW
import kohii.core.Master.MemoryMode.NORMAL
import kohii.logInfo
import kohii.logWarn
import kohii.media.Media
import kohii.media.PlaybackInfo
import kohii.media.VolumeInfo
import kohii.v1.Bridge
import kotlin.properties.Delegates

abstract class Playable<RENDERER : Any>(
  val master: Master,
  val media: Media,
  val config: Config,
  internal val rendererType: Class<RENDERER>,
  internal val bridge: Bridge<RENDERER>
) : Playback.Callback,
    Playback.RendererHolderListener,
    Playback.DistanceListener,
    Playback.VolumeInfoListener,
    Playback.PlaybackInfoListener,
    Playback.Unbinder {

  data class Config(
    val tag: Any = NO_TAG
  )

  private var playRequested: Boolean = false

  val tag: Any = config.tag

  override fun toString(): String {
    return "${super.toString()}, [$tag]"
  }

  // Ensure the preparation for the playback
  internal fun onReady() {
    "Playable#onReady $this".logInfo()
    bridge.ensurePreparation()
  }

  /**
   * Return **true** to indicate that this Playable would survive configuration changes and no
   * playback reloading would be required. In special cases like YouTube playback, it is recommended
   * to return **false** so Kohii will handle the resource recycling correctly.
   */
  protected open fun onConfigChange(): Boolean {
    "Playable#onConfigChange $this".logInfo()
    return true
  }

  open fun onPlay() {
    "Playable#onPlay $this".logWarn()
    playback?.onPlay()
    if (!playRequested) {
      playRequested = true
      bridge.play()
    }
  }

  open fun onPause() {
    "Playable#onPause $this".logWarn()
    if (playRequested) {
      playRequested = false
      bridge.pause()
    }
    playback?.onPause()
  }

  open fun onRelease() {
    "Playable#onRelease $this".logInfo()
    master.trySavePlaybackInfo(this)
    master.releasePlayable(this)
  }

  private val memoryMode: MemoryMode
    get() = (manager as? Manager)?.memoryMode ?: LOW

  internal var manager: PlayableManager? by Delegates.observable<PlayableManager?>(
      null,
      onChange = { _, from, to ->
        if (from === to) return@observable
        "Playable#manager $from --> $to, $this".logInfo()
        if (to == null) {
          master.trySavePlaybackInfo(this)
          master.tearDown(this, false)
        } else if (from === null) {
          master.tryRestorePlaybackInfo(this)
        }
      }
  )

  @Suppress("IfThenToElvis")
  internal var playback: Playback? by Delegates.observable<Playback?>(null,
      onChange = { _, from, to ->
        if (from === to) return@observable
        "Playable#playback $from --> $to, $this".logInfo()
        if (from != null) {
          bridge.removeErrorListener(from)
          bridge.removeEventListener(from)
          from.removeCallback(this)
          if (from.unbinder === this) from.unbinder = null
          if (from.playbackInfoListener === this) from.playbackInfoListener = null
          if (from.volumeInfoListener === this) from.volumeInfoListener = null
          if (from.rendererHolderListener === this) from.rendererHolderListener = null
          if (from.distanceListener === this) from.distanceListener = null
        }

        this.manager =
          if (to != null) {
            to.manager
          } else {
            val configChange =
              if (from != null) {
                from.manager.group.activity.isChangingConfigurations
              } else {
                false
              }

            if (!configChange) null
            else if (!onConfigChange()) {
              // on config change, if the Playable doesn't support, we need to pause the Video.
              onPause() // TODO check why it doesn't work for YouTube demo.
              null
            } else {
              master // to prevent the Playable from being destroyed when Manager is null.
            }
          }

        if (to != null) {
          to.addCallback(this)
          to.config.callbacks.forEach { cb -> to.addCallback(cb) }
          bridge.addEventListener(to)
          bridge.addErrorListener(to)
          to.distanceListener = this
          to.rendererHolderListener = this
          to.volumeInfoListener = this
          to.playbackInfoListener = this
          to.unbinder = this
        }
      }
  )

  internal val playerState: Int
    get() = bridge.playbackState

  // Playback.Callback

  override fun onActive(playback: Playback) {
    "Playable#onActive $playback, $this".logInfo()
    require(playback === this.playback)
    master.tryRestorePlaybackInfo(this)
    master.preparePlayable(this, playback.config.preload)
  }

  override fun onInActive(playback: Playback) {
    "Playable#onInActive $playback, $this".logInfo()
    require(playback === this.playback)
    val configChange = playback.manager.group.activity.isChangingConfigurations
    if (!configChange) {
      master.trySavePlaybackInfo(this)
      master.releasePlayable(this)
    }
  }

  override fun onAdded(playback: Playback) {
    "Playable#onAdded $playback, $this".logInfo()
    bridge.repeatMode = playback.config.repeatMode
    bridge.setVolumeInfo(playback.volumeInfo)
  }

  override fun onRemoved(playback: Playback) {
    "Playable#onRemoved $playback, $this".logInfo()
    require(playback === this.playback)
    this.playback = null // Will also clear current Manager.
  }

  @CallSuper
  override fun considerRequestRenderer(playback: Playback) {
    "Playable#considerRequestRenderer $playback, $this".logInfo()
    require(playback === this.playback)
    if (manager !== playback.manager || bridge.playerView == null) { // Only request for Renderer if we do not have one.
      val renderer = playback.manager.requestRenderer(playback, this)
      bridge.playerView = renderer
    }
  }

  @CallSuper
  override fun considerReleaseRenderer(playback: Playback) {
    "Playable#considerReleaseRenderer $playback, $this".logInfo()
    require(this.playback == null || this.playback === playback)
    if (bridge.playerView != null) { // Only release the Renderer if we do have one to release.
      bridge.playerView = null
      playback.manager.releaseRenderer(playback, this)
    }
  }

  // Playback.DistanceListener

  override fun onDistanceChanged(
    playback: Playback,
    from: Int,
    to: Int
  ) {
    "Playable#onDistanceChanged $playback, $from --> $to, $this".logInfo()
    if (to == 0) {
      master.tryRestorePlaybackInfo(this)
      master.preparePlayable(this, playback.config.preload)
    } else {
      val memoryMode = master.preferredMemoryMode(this.memoryMode)
      val distanceToRelease =
        when (memoryMode) {
          AUTO, LOW -> 1
          NORMAL -> 2
          BALANCED -> 2 // Same as 'NORMAL', but will keep the 'relative' Playback alive.
          HIGH -> 8
          INFINITE -> Int.MAX_VALUE - 1
        }
      if (to >= distanceToRelease) {
        master.trySavePlaybackInfo(this)
        master.releasePlayable(this)
      } else {
        if (memoryMode != BALANCED) {
          bridge.reset(false)
        }
      }
    }
  }

  // Playback.VolumeInfoListener

  override fun onVolumeInfoChange(
    playback: Playback,
    from: VolumeInfo,
    to: VolumeInfo
  ) {
    "Playable#onVolumeInfoChange $playback, $from --> $to, $this".logInfo()
    bridge.setVolumeInfo(to)
  }

  // Playback.PlaybackInfoListener

  override var playbackInfo: PlaybackInfo
    get() = bridge.playbackInfo
    set(value) {
      "Playable#playbackInfo setter $value, $this".logInfo()
      bridge.playbackInfo = value
    }

  // Playback.Unbindable

  override fun onUnbind(playback: Playback) {
    "Playable#onUnbind $playback, $this".logInfo()
    if (this.playback === playback) {
      playback.manager.removePlayback(playback)
    }
  }
}
