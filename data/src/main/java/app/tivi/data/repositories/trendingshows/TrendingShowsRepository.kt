/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.data.repositories.trendingshows

import app.tivi.data.daos.TiviShowDao
import app.tivi.data.entities.Success
import app.tivi.data.fetch
import app.tivi.data.instantInPast
import app.tivi.data.repositories.ShowImagesStore
import app.tivi.data.repositories.ShowStore
import app.tivi.extensions.asyncOrAwait
import app.tivi.extensions.parallelForEach
import javax.inject.Inject
import javax.inject.Singleton
import org.threeten.bp.Instant

@Singleton
class TrendingShowsRepository @Inject constructor(
    private val trendingShowsStore: TrendingShowsStore,
    private val lastRequestStore: TrendingShowsLastRequestStore,
    private val traktDataSource: TraktTrendingShowsDataSource,
    private val showDao: TiviShowDao,
    private val showStore: ShowStore,
    private val showImagesStore: ShowImagesStore
) {
    fun observeForPaging() = trendingShowsStore.observeForPaging()

    fun observeForObservable() = trendingShowsStore.observeForObservable(15, 0)

    suspend fun loadNextPage() {
        val lastPage = trendingShowsStore.getLastPage()
        if (lastPage != null) updateTrendingShows(lastPage + 1, false) else update()
    }

    suspend fun needUpdate(expiry: Instant = instantInPast(hours = 3)): Boolean {
        return lastRequestStore.isRequestBefore(expiry)
    }

    suspend fun update() {
        updateTrendingShows(0, true)
        lastRequestStore.updateLastRequest()
    }

    private suspend fun updateTrendingShows(page: Int, resetOnSave: Boolean) {
        asyncOrAwait("update_trending_page$page") {
            val response = traktDataSource.getTrendingShows(page, 20)
            if (response is Success) {
                response.data.map { (show, entry) ->
                    // Grab the show id if it exists, or save the show and use it's generated ID
                    val showId = showDao.getIdOrSavePlaceholder(show)
                    // Make a copy of the entry with the id
                    entry.copy(showId = showId, page = page)
                }.also { entries ->
                    if (resetOnSave) {
                        trendingShowsStore.deleteAll()
                    }
                    // Save the related entriesWithShows
                    trendingShowsStore.saveTrendingShowsPage(page, entries)
                    // Now update all of the related shows if needed
                    entries.parallelForEach { entry ->
                        showStore.fetch(entry.showId)
                        showImagesStore.fetch(entry.showId)
                    }
                }
            }
        }
    }
}
