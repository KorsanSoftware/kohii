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

package kohii.v1

import androidx.lifecycle.Lifecycle.Event.ON_DESTROY
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import kohii.media.Media

class NoCacheRendererPool<RENDERER : Any>(
  val kohii: Kohii,
  private val creator: RendererCreator<RENDERER>
) : RendererPool<RENDERER>, LifecycleObserver {

  override fun <CONTAINER : Any> acquireRenderer(
    playback: Playback<RENDERER>,
    target: Target<CONTAINER, RENDERER>,
    media: Media
  ): RENDERER {
    val container = target.container
    val mediaType = creator.getMediaType(media)
    return creator.createRenderer(playback, container, mediaType)
  }

  override fun <CONTAINER : Any> releaseRenderer(
    target: Target<CONTAINER, RENDERER>,
    renderer: RENDERER,
    media: Media
  ) {
    // Do nothing. We just let the renderer be GCed.
  }

  override fun cleanUp() {
    // No-ops
  }

  @OnLifecycleEvent(ON_DESTROY)
  fun onOwnerDestroy(owner: LifecycleOwner) {
    owner.lifecycle.removeObserver(this)
    kohii.cleanUpPool(owner, this)
  }
}