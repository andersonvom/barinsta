package awais.instagrabber.fragments;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavDirections;
import androidx.navigation.fragment.NavHostFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import awais.instagrabber.R;
import awais.instagrabber.activities.MainActivity;
import awais.instagrabber.adapters.PostsAdapter;
import awais.instagrabber.asyncs.LocationFetcher;
import awais.instagrabber.asyncs.PostsFetcher;
import awais.instagrabber.asyncs.i.iStoryStatusFetcher;
import awais.instagrabber.customviews.PrimaryActionModeCallback;
import awais.instagrabber.customviews.helpers.GridAutofitLayoutManager;
import awais.instagrabber.customviews.helpers.GridSpacingItemDecoration;
import awais.instagrabber.customviews.helpers.NestedCoordinatorLayout;
import awais.instagrabber.customviews.helpers.RecyclerLazyLoader;
import awais.instagrabber.databinding.FragmentLocationBinding;
import awais.instagrabber.interfaces.FetchListener;
import awais.instagrabber.models.LocationModel;
import awais.instagrabber.models.PostModel;
import awais.instagrabber.models.StoryModel;
import awais.instagrabber.models.enums.DownloadMethod;
import awais.instagrabber.models.enums.PostItemType;
import awais.instagrabber.utils.Constants;
import awais.instagrabber.utils.CookieUtils;
import awais.instagrabber.utils.DownloadUtils;
import awais.instagrabber.utils.TextUtils;
import awais.instagrabber.utils.Utils;
import awais.instagrabber.viewmodels.PostsViewModel;
import awaisomereport.LogCollector;

import static awais.instagrabber.utils.Utils.logCollector;
import static awais.instagrabber.utils.Utils.settingsHelper;

public class LocationFragment extends Fragment {
    private static final String TAG = "LocationFragment";

    private MainActivity fragmentActivity;
    private FragmentLocationBinding binding;
    private NestedCoordinatorLayout root;
    private boolean shouldRefresh = true;
    private String locationId;
    private LocationModel locationModel;
    private PostsViewModel postsViewModel;
    private PostsAdapter postsAdapter;
    private ActionMode actionMode;
    private boolean hasNextPage;
    private String endCursor;
    private AsyncTask<?, ?, ?> currentlyExecuting;
    private boolean isLoggedIn;
    private StoryModel[] storyModels;

    private final OnBackPressedCallback onBackPressedCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (postsAdapter == null) {
                setEnabled(false);
                remove();
                return;
            }
            postsAdapter.clearSelection();
            setEnabled(false);
            remove();
        }
    };
    private final PrimaryActionModeCallback multiSelectAction = new PrimaryActionModeCallback(
            R.menu.multi_select_download_menu, new PrimaryActionModeCallback.CallbacksHelper() {
        @Override
        public void onDestroy(final ActionMode mode) {
            onBackPressedCallback.handleOnBackPressed();
        }

        @Override
        public boolean onActionItemClicked(final ActionMode mode,
                                           final MenuItem item) {
            if (item.getItemId() == R.id.action_download) {
                if (postsAdapter == null || locationId == null) {
                    return false;
                }
                final Context context = getContext();
                if (context == null) return false;
                DownloadUtils.batchDownload(context,
                                            locationId,
                                            DownloadMethod.DOWNLOAD_MAIN,
                                            postsAdapter.getSelectedModels());
                checkAndResetAction();
                return true;
            }
            return false;
        }
    });
    private final FetchListener<PostModel[]> postsFetchListener = new FetchListener<PostModel[]>() {
        @Override
        public void onResult(final PostModel[] result) {
            binding.swipeRefreshLayout.setRefreshing(false);
            if (result == null) return;
            binding.mainPosts.post(() -> binding.mainPosts.setVisibility(View.VISIBLE));
            final List<PostModel> postModels = postsViewModel.getList().getValue();
            final List<PostModel> finalList = postModels == null || postModels.isEmpty() ? new ArrayList<>()
                                                                                         : new ArrayList<>(postModels);
            finalList.addAll(Arrays.asList(result));
            postsViewModel.getList().postValue(finalList);
            PostModel model = null;
            if (result.length != 0) {
                model = result[result.length - 1];
            }
            if (model == null) return;
            endCursor = model.getEndCursor();
            hasNextPage = model.hasNextPage();
            model.setPageCursor(false, null);
        }
    };

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fragmentActivity = (MainActivity) requireActivity();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        if (root != null) {
            shouldRefresh = false;
            return root;
        }
        binding = FragmentLocationBinding.inflate(inflater, container, false);
        root = binding.getRoot();
        return root;
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        if (!shouldRefresh) return;
        init();
        shouldRefresh = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (postsViewModel != null) {
            postsViewModel.getList().postValue(Collections.emptyList());
        }
    }

    private void init() {
        if (getArguments() == null) return;
        final String cookie = settingsHelper.getString(Constants.COOKIE);
        isLoggedIn = !TextUtils.isEmpty(cookie) && CookieUtils.getUserIdFromCookie(cookie) != null;
        final LocationFragmentArgs fragmentArgs = LocationFragmentArgs.fromBundle(getArguments());
        locationId = fragmentArgs.getLocationId();
        setTitle();
        setupPosts();
        fetchLocationModel();
    }

    private void setupPosts() {
        postsViewModel = new ViewModelProvider(this).get(PostsViewModel.class);
        final Context context = getContext();
        if (context == null) return;
        final GridAutofitLayoutManager layoutManager = new GridAutofitLayoutManager(context, Utils.convertDpToPx(110));
        binding.mainPosts.setLayoutManager(layoutManager);
        binding.mainPosts.addItemDecoration(new GridSpacingItemDecoration(Utils.convertDpToPx(4)));
        postsAdapter = new PostsAdapter((postModel, position) -> {
            if (postsAdapter.isSelecting()) {
                if (actionMode == null) return;
                final String title = getString(R.string.number_selected, postsAdapter.getSelectedModels().size());
                actionMode.setTitle(title);
                return;
            }
            if (checkAndResetAction()) return;
            final List<PostModel> postModels = postsViewModel.getList().getValue();
            if (postModels == null || postModels.size() == 0) return;
            if (postModels.get(0) == null) return;
            final String postId = postModels.get(0).getPostId();
            final boolean isId = postId != null;
            final String[] idsOrShortCodes = new String[postModels.size()];
            for (int i = 0; i < postModels.size(); i++) {
                idsOrShortCodes[i] = isId ? postModels.get(i).getPostId()
                                          : postModels.get(i).getShortCode();
            }
            final NavDirections action = LocationFragmentDirections.actionGlobalPostViewFragment(
                    position,
                    idsOrShortCodes,
                    isId);
            NavHostFragment.findNavController(this).navigate(action);
        }, (model, position) -> {
            if (!postsAdapter.isSelecting()) {
                checkAndResetAction();
                return true;
            }
            if (onBackPressedCallback.isEnabled()) {
                return true;
            }
            final OnBackPressedDispatcher onBackPressedDispatcher = fragmentActivity
                    .getOnBackPressedDispatcher();
            onBackPressedCallback.setEnabled(true);
            actionMode = fragmentActivity.startActionMode(multiSelectAction);
            final String title = getString(R.string.number_selected, 1);
            actionMode.setTitle(title);
            onBackPressedDispatcher.addCallback(getViewLifecycleOwner(), onBackPressedCallback);
            return true;
        });
        postsViewModel.getList().observe(fragmentActivity, postsAdapter::submitList);
        binding.mainPosts.setAdapter(postsAdapter);
        final RecyclerLazyLoader lazyLoader = new RecyclerLazyLoader(layoutManager, (page, totalItemsCount) -> {
            if (!hasNextPage) return;
            binding.swipeRefreshLayout.setRefreshing(true);
            fetchPosts();
            endCursor = null;
        });
        binding.mainPosts.addOnScrollListener(lazyLoader);
    }

    private void fetchLocationModel() {
        stopCurrentExecutor();
        binding.swipeRefreshLayout.setRefreshing(true);
        currentlyExecuting = new LocationFetcher(locationId, result -> {
            locationModel = result;
            binding.swipeRefreshLayout.setRefreshing(false);
            if (locationModel == null) {
                final Context context = getContext();
                if (context == null) return;
                Toast.makeText(context, R.string.error_loading_profile, Toast.LENGTH_SHORT).show();
                return;
            }
            setTitle();
            setupLocationDetails();
            fetchPosts();
        }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void setupLocationDetails() {
        final String locationId = locationModel.getId();
        binding.swipeRefreshLayout.setRefreshing(true);
        if (isLoggedIn) {
            new iStoryStatusFetcher(
                    locationId,
                    null,
                    true,
                    false,
                    false,
                    false,
                    stories -> {
                        storyModels = stories;
                        if (stories != null && stories.length > 0) {
                            binding.mainLocationImage.setStoriesBorder();
                        }
                    }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
        binding.mainLocationImage.setImageURI(locationModel.getSdProfilePic());
        final String postCount = String.valueOf(locationModel.getPostCount());
        final SpannableStringBuilder span = new SpannableStringBuilder(getString(R.string.main_posts_count,
                                                                                 postCount));
        span.setSpan(new RelativeSizeSpan(1.2f), 0, postCount.length(), 0);
        span.setSpan(new StyleSpan(Typeface.BOLD), 0, postCount.length(), 0);
        binding.mainLocPostCount.setText(span);
        binding.mainLocPostCount.setVisibility(View.VISIBLE);
        binding.locationFullName.setText(locationModel.getName());
        CharSequence biography = locationModel.getBio();
        binding.locationBiography.setCaptionIsExpandable(true);
        binding.locationBiography.setCaptionIsExpanded(true);

        if (TextUtils.isEmpty(biography)) {
            binding.locationBiography.setVisibility(View.GONE);
        } else if (TextUtils.hasMentions(biography)) {
            binding.locationBiography.setVisibility(View.VISIBLE);
            biography = TextUtils.getMentionText(biography);
            binding.locationBiography.setText(biography, TextView.BufferType.SPANNABLE);
            // binding.locationBiography.setMentionClickListener(mentionClickListener);
        } else {
            binding.locationBiography.setVisibility(View.VISIBLE);
            binding.locationBiography.setText(biography);
            binding.locationBiography.setMentionClickListener(null);
        }

        if (!locationModel.getGeo().startsWith("geo:0.0,0.0?z=17")) {
            binding.btnMap.setVisibility(View.VISIBLE);
            binding.btnMap.setOnClickListener(v -> {
                final Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(locationModel.getGeo()));
                startActivity(intent);
            });
        } else {
            binding.btnMap.setVisibility(View.GONE);
            binding.btnMap.setOnClickListener(null);
        }

        final String url = locationModel.getUrl();
        if (TextUtils.isEmpty(url)) {
            binding.locationUrl.setVisibility(View.GONE);
        } else if (!url.startsWith("http")) {
            binding.locationUrl.setVisibility(View.VISIBLE);
            binding.locationUrl.setText(TextUtils.getSpannableUrl("http://" + url));
        } else {
            binding.locationUrl.setVisibility(View.VISIBLE);
            binding.locationUrl.setText(TextUtils.getSpannableUrl(url));
        }
    }

    private void fetchPosts() {
        stopCurrentExecutor();
        currentlyExecuting = new PostsFetcher(locationModel.getId(), PostItemType.LOCATION, endCursor, postsFetchListener)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void stopCurrentExecutor() {
        if (currentlyExecuting != null) {
            try {
                currentlyExecuting.cancel(true);
            } catch (final Exception e) {
                if (logCollector != null) logCollector.appendException(
                        e, LogCollector.LogFile.MAIN_HELPER, "stopCurrentExecutor");
                Log.e(TAG, "", e);
            }
        }
    }

    private void setTitle() {
        final ActionBar actionBar = fragmentActivity.getSupportActionBar();
        if (actionBar != null && locationModel != null) {
            actionBar.setTitle(locationModel.getName());
        }
    }

    private boolean checkAndResetAction() {
        if (!onBackPressedCallback.isEnabled() && actionMode == null) {
            return false;
        }
        if (onBackPressedCallback.isEnabled()) {
            onBackPressedCallback.setEnabled(false);
            onBackPressedCallback.remove();
        }
        if (actionMode != null) {
            actionMode.finish();
            actionMode = null;
        }
        return true;
    }
}
