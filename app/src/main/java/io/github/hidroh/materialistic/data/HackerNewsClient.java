/*
 * Copyright (c) 2015 Ha Duy Trung
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

package io.github.hidroh.materialistic.data;

import android.content.ContentResolver;
import android.content.Context;
import android.support.annotation.NonNull;

import javax.inject.Inject;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.Path;

/**
 * Client to retrieve Hacker News content asynchronously
 */
public class HackerNewsClient implements ItemManager, UserManager {
    public static final String BASE_WEB_URL = "https://news.ycombinator.com";
    public static final String WEB_ITEM_PATH = BASE_WEB_URL + "/item?id=%s";
    private static final String BASE_API_URL = "https://hacker-news.firebaseio.com/v0/";
    private final RestService mRestService;
    private final SessionManager mSessionManager;
    private final FavoriteManager mFavoriteManager;
    private final ContentResolver mContentResolver;

    @Inject
    public HackerNewsClient(Context context, RestServiceFactory factory,
                            SessionManager sessionManager,
                            FavoriteManager favoriteManager) {
        mRestService = factory.create(BASE_API_URL, RestService.class);
        mSessionManager = sessionManager;
        mFavoriteManager = favoriteManager;
        mContentResolver = context.getApplicationContext().getContentResolver();
    }

    @Override
    public void getStories(@FetchMode String filter, final ResponseListener<Item[]> listener) {
        if (listener == null) {
            return;
        }
        Call<int[]> call;
        switch (filter) {
            case NEW_FETCH_MODE:
                call = mRestService.newStories();
                break;
            case SHOW_FETCH_MODE:
                call = mRestService.showStories();
                break;
            case ASK_FETCH_MODE:
                call = mRestService.askStories();
                break;
            case JOBS_FETCH_MODE:
                call = mRestService.jobStories();
                break;
            default:
                call = mRestService.topStories();
                break;
        }
        call.enqueue(new Callback<int[]>() {
            @Override
            public void onResponse(Call<int[]> call, Response<int[]> response) {
                listener.onResponse(toItems(response.body()));
            }

            @Override
            public void onFailure(Call<int[]> call, Throwable t) {
                listener.onError(t != null ? t.getMessage() : "");

            }
        });
    }

    @Override
    public void getItem(String itemId, final ResponseListener<Item> listener) {
        if (listener == null) {
            return;
        }
        ItemCallbackWrapper wrapper = new ItemCallbackWrapper(listener);
        mSessionManager.isViewed(mContentResolver, itemId, wrapper);
        mFavoriteManager.check(mContentResolver, itemId, wrapper);
        mRestService.item(itemId).enqueue(wrapper);
    }

    @Override
    public void getUser(String username, final ResponseListener<User> listener) {
        if (listener == null) {
            return;
        }
        mRestService.user(username)
                .enqueue(new Callback<UserItem>() {
                    @Override
                    public void onResponse(Call<UserItem> call, Response<UserItem> response) {
                        UserItem user = response.body();
                        if (user == null) {
                            listener.onResponse(null);
                            return;
                        }
                        user.setSubmittedItems(toItems(user.getSubmitted()));
                        listener.onResponse(user);
                    }

                    @Override
                    public void onFailure(Call<UserItem> call, Throwable t) {
                        listener.onError(t != null ? t.getMessage() : "");
                    }
                });
    }

    @NonNull
    private HackerNewsItem[] toItems(int[] ids) {
        HackerNewsItem[] items = new HackerNewsItem[ids == null ? 0 : ids.length];
        for (int i = 0; i < items.length; i++) {
            HackerNewsItem item = new HackerNewsItem(ids[i]);
            item.rank = i + 1;
            items[i] = item;
        }
        return items;
    }

    interface RestService {
        @Headers("Cache-Control: max-age=600")
        @GET("topstories.json")
        Call<int[]> topStories();

        @Headers("Cache-Control: max-age=600")
        @GET("newstories.json")
        Call<int[]> newStories();

        @Headers("Cache-Control: max-age=600")
        @GET("showstories.json")
        Call<int[]> showStories();

        @Headers("Cache-Control: max-age=600")
        @GET("askstories.json")
        Call<int[]> askStories();

        @Headers("Cache-Control: max-age=600")
        @GET("jobstories.json")
        Call<int[]> jobStories();

        @Headers("Cache-Control: max-age=300")
        @GET("item/{itemId}.json")
        Call<HackerNewsItem> item(@Path("itemId") String itemId);

        @Headers("Cache-Control: max-age=300")
        @GET("user/{userId}.json")
        Call<UserItem> user(@Path("userId") String userId);
    }

    private static class ItemCallbackWrapper implements SessionManager.OperationCallbacks,
            FavoriteManager.OperationCallbacks, Callback<HackerNewsItem> {
        private final ResponseListener<Item> responseListener;
        private Boolean isViewed;
        private Boolean isFavorite;
        private Item item;
        private String errorMessage;
        private boolean hasError;
        private boolean hasResponse;

        private ItemCallbackWrapper(@NonNull ResponseListener<Item> responseListener) {
            this.responseListener = responseListener;
        }

        @Override
        public void onCheckViewedComplete(boolean isViewed) {
            this.isViewed = isViewed;
            done();
        }

        @Override
        public void onCheckComplete(boolean isFavorite) {
            this.isFavorite = isFavorite;
            done();
        }

        @Override
        public void onResponse(Call<HackerNewsItem> call, Response<HackerNewsItem> response) {
            this.item = response.body();
            this.hasResponse = true;
            done();
        }

        @Override
        public void onFailure(Call<HackerNewsItem> call, Throwable t) {
            this.errorMessage = t != null ? t.getMessage() : "";
            this.hasError = true;
            done();
        }

        private void done() {
            if (isViewed == null) {
                return;
            }
            if (isFavorite == null) {
                return;
            }
            if (!(hasResponse || hasError)) {
                return;
            }
            if (hasResponse) {
                if (item != null) {
                    item.setFavorite(isFavorite);
                    item.setIsViewed(isViewed);
                }
                responseListener.onResponse(item);
            } else {
                responseListener.onError(errorMessage);
            }
        }
    }
}
