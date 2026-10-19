package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager2.widget.ViewPager2;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.tmdb.TmdbApi;
import com.fongmi.android.tv.api.tmdb.bean.TmdbDetail;
import com.fongmi.android.tv.api.tmdb.bean.TmdbMedia;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVodDetailBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.adapter.TmdbBackdropAdapter;
import com.fongmi.android.tv.ui.adapter.TmdbCastAdapter;
import com.fongmi.android.tv.ui.adapter.TmdbCrewAdapter;
import com.fongmi.android.tv.ui.adapter.TmdbRecommendAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.TmdbRematchDialog;
import com.fongmi.android.tv.utils.BlurUtil;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * TMDB 原画沉浸大画报详情页。
 * 严格遵照用户需求与截图设计规范：
 * 1. 全屏高斯模糊海报底层背景图（非纯色背景，采用影片海报渲染，对齐截图2）；
 * 2. 第一屏海报占比调大，直达简介（海报高 = 屏幕高 - 165dp）；
 * 3. 简介3行折叠显示，点击展开/收起；
 * 4. 轮播无圆点指示器，5s 自动平滑轮播；
 * 5. 磨砂质感播放药丸大按钮；
 * 6. 不展示选集列表，直接起播；
 * 7. 演职人员横滑列表（圆形头像 + 演员名 + 角色名）；
 * 8. 主创团队横滑列表（圆形头像 + 主创名 + 职位）；
 * 9. 猜你喜欢横滑推荐（圆角海报 + 片名，支持直接点击检索直达）。
 */
public class VodDetailActivity extends BaseActivity {

    private ActivityVodDetailBinding mBinding;
    private SiteViewModel mViewModel;
    private TmdbBackdropAdapter mBackdropAdapter;
    private TmdbCastAdapter mCastAdapter;
    private TmdbCrewAdapter mCrewAdapter;
    private TmdbRecommendAdapter mRecommendAdapter;

    private final Handler mSliderHandler = new Handler(Looper.getMainLooper());
    private Runnable mSliderRunnable;

    private Vod mVod;
    private Flag mCurrentFlag;
    private Episode mTargetEpisode;
    private TmdbDetail mTmdbDetail;
    private boolean mOverviewExpanded = false;

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, String wallPic, String content) {
        ImgUtil.preload(activity, pic);
        Intent intent = new Intent(activity, VodDetailActivity.class);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("mark", mark);
        intent.putExtra("collect", collect);
        intent.putExtra("wallPic", wallPic);
        intent.putExtra("content", content);
        activity.startActivity(intent);
    }

    public String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    public String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    public String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    public String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    public String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    public boolean isCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    public String getWallPic() {
        return Objects.toString(getIntent().getStringExtra("wallPic"), "");
    }

    public String getContent() {
        return Objects.toString(getIntent().getStringExtra("content"), "");
    }

    private String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVodDetailBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            mBottomInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            mBinding.topBar.setPadding(mBinding.topBar.getPaddingLeft(), top + ResUtil.dp2px(8), mBinding.topBar.getPaddingRight(), mBinding.topBar.getPaddingBottom());
            ViewGroup.LayoutParams lp = mBinding.topScrim.getLayoutParams();
            if (lp != null) {
                lp.height = top + ResUtil.dp2px(56);
                mBinding.topScrim.setLayoutParams(lp);
            }
            adjustHeroToFitFirstScreen();
            return insets;
        });
        adjustHeroHeight();
        initViewPager();
        initInitialContent();
        initRecyclers();
        initEvents();
        initViewModel();
        loadData();
        adjustHeroToFitFirstScreen();
    }

    private int mBottomInset = 0;

    private void adjustHeroHeight() {
        int screenH = ResUtil.getScreenHeight(this);
        // 大幅扩大海报占比，初始高度为屏幕高度减去底部信息预估高度，确保首屏以大画报为主体
        int heroHeight = Math.max(ResUtil.dp2px(380), screenH - ResUtil.dp2px(230));
        ViewGroup.LayoutParams lp = mBinding.heroBox.getLayoutParams();
        if (lp != null) {
            lp.height = heroHeight;
            mBinding.heroBox.setLayoutParams(lp);
        }
    }

    private void adjustHeroToFitFirstScreen() {
        if (mOverviewExpanded) return;
        mBinding.infoContentBox.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            int availableH = mBinding.scrollView.getHeight();
            if (availableH <= 0) availableH = ResUtil.getScreenHeight(this);
            availableH -= mBottomInset;

            int infoH = mBinding.infoContentBox.getHeight();
            if (infoH <= 0) {
                int widthSpec = View.MeasureSpec.makeMeasureSpec(
                        Math.max(0, ResUtil.getScreenWidth(this) - ResUtil.dp2px(40)),
                        View.MeasureSpec.EXACTLY
                );
                int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
                mBinding.infoContentBox.measure(widthSpec, heightSpec);
                infoH = mBinding.infoContentBox.getMeasuredHeight();
            }

            int minHeroH = ResUtil.dp2px(360);
            int targetHeroH = Math.max(minHeroH, availableH - infoH);

            ViewGroup.LayoutParams lp = mBinding.heroBox.getLayoutParams();
            if (lp != null && lp.height != targetHeroH) {
                lp.height = targetHeroH;
                mBinding.heroBox.setLayoutParams(lp);
            }
        });
    }

    private void initInitialContent() {
        mBinding.titleText.setText(getName());
        mBinding.ratingBadge.setVisibility(View.GONE);
        mBinding.originalTitleText.setVisibility(View.GONE);
        mBinding.metaText.setText(!TextUtils.isEmpty(getMark()) ? getMark() : "");

        if (!TextUtils.isEmpty(getContent())) {
            mBinding.overviewText.setText(getContent());
            mBinding.overviewText.setVisibility(View.VISIBLE);
        }

        String initialPic = !TextUtils.isEmpty(getWallPic()) ? getWallPic() : getPic();
        if (!TextUtils.isEmpty(initialPic)) {
            ImgUtil.load(getName(), initialPic, mBinding.backdropBlur);
            mBackdropAdapter.setItems(Collections.singletonList(initialPic));
            // 加载底层高斯模糊海报背景
            BlurUtil.loadBlur(this, initialPic, mBinding.globalBlurBg);
        }
    }

    private void initRecyclers() {
        // 演职人员
        mCastAdapter = new TmdbCastAdapter();
        mBinding.castRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mBinding.castRecycler.setAdapter(mCastAdapter);

        // 主创团队
        mCrewAdapter = new TmdbCrewAdapter();
        mBinding.crewRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mBinding.crewRecycler.setAdapter(mCrewAdapter);

        // 猜你喜欢
        mRecommendAdapter = new TmdbRecommendAdapter();
        mRecommendAdapter.setListener(item -> {
            if (item != null && !TextUtils.isEmpty(item.getDisplayTitle())) {
                SearchActivity.start(this, item.getDisplayTitle());
            }
        });
        mBinding.recommendRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        mBinding.recommendRecycler.setAdapter(mRecommendAdapter);
    }

    private void initEvents() {
        mBinding.backButton.setOnClickListener(v -> finish());

        mBinding.playButton.setOnClickListener(v -> {
            if (mTargetEpisode != null) {
                startPlayback(mTargetEpisode);
            } else {
                VideoActivity.start(this, getKey(), getId(), getName(), getPic(), getMark(), isCollect(), getWallPic(), getContent());
            }
        });

        // 简介3行折叠，点击展开/收起
        mBinding.overviewText.setOnClickListener(v -> {
            mOverviewExpanded = !mOverviewExpanded;
            mBinding.overviewText.setMaxLines(mOverviewExpanded ? Integer.MAX_VALUE : 3);
            if (!mOverviewExpanded) {
                adjustHeroToFitFirstScreen();
            }
        });

        // 右上角重新匹配按钮
        mBinding.rematchButton.setOnClickListener(v -> {
            String initialKeyword = mTmdbDetail != null && !TextUtils.isEmpty(mTmdbDetail.getDisplayTitle())
                    ? mTmdbDetail.getDisplayTitle()
                    : getName();
            TmdbRematchDialog.create(initialKeyword, getName(), detail -> {
                mTmdbDetail = detail;
                onTmdbDetailLoaded(detail);
            }).show(this);
        });
    }

    private void initViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, this::onSiteDetailResult);
    }

    private void loadData() {
        mBinding.loadingOverlay.setVisibility(View.VISIBLE);
        // 1. 请求源站详情（解析可播放剧集与备用简介）
        mViewModel.detailContent(getKey(), getId());

        // 2. 异步请求 TMDB 高清画报、演职人员、主创团队与推荐
        TmdbApi.get().searchAndGetDetail(getName(), new TmdbApi.DetailCallback() {
            @Override
            public void onSuccess(TmdbDetail detail) {
                runOnUiThread(() -> {
                    mTmdbDetail = detail;
                    onTmdbDetailLoaded(detail);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    mBinding.loadingOverlay.setVisibility(View.GONE);
                });
            }
        });
    }

    private void onSiteDetailResult(Result result) {
        if (result == null || result.getList().isEmpty()) return;
        mVod = result.getVod();
        if (mVod == null) return;

        // 若 TMDB 暂未提供剧情，使用源站剧情
        if (mTmdbDetail == null && !TextUtils.isEmpty(mVod.getContent())) {
            mBinding.overviewText.setText(mVod.getContent());
            adjustHeroToFitFirstScreen();
        }

        // 解析备用播放剧集
        setupEpisodes(mVod);
    }

    private void setupEpisodes(Vod vod) {
        List<Flag> flags = vod.getFlags();
        if (flags == null || flags.isEmpty()) return;

        History history = History.find(getHistoryKey());
        Flag targetFlag = flags.get(0);
        if (history != null && !TextUtils.isEmpty(history.getVodFlag())) {
            for (Flag f : flags) {
                if (history.getVodFlag().equals(f.getFlag())) {
                    targetFlag = f;
                    break;
                }
            }
        }
        mCurrentFlag = targetFlag;

        List<Episode> episodes = targetFlag.getEpisodes();
        if (episodes != null && !episodes.isEmpty()) {
            Episode target = episodes.get(0);
            if (history != null && !TextUtils.isEmpty(history.getEpisodeUrl())) {
                for (Episode ep : episodes) {
                    if (history.getEpisodeUrl().equals(ep.getUrl())) {
                        target = ep;
                        break;
                    }
                }
            }
            mTargetEpisode = target;
        }
    }

    private void onTmdbDetailLoaded(TmdbDetail detail) {
        mBinding.loadingOverlay.setVisibility(View.GONE);
        if (detail == null) return;

        // 1. 超清画报轮播（原画质量，优先竖版大画报，无圆点指示器）
        List<String> backdropUrls = detail.getAllBackdropUrls();
        if (!backdropUrls.isEmpty()) {
            mBackdropAdapter.setItems(backdropUrls);
            startAutoSlider();
            // 全屏高斯模糊海报背景（参考截图2：非纯色，由当前海报渲染高斯模糊背景）
            BlurUtil.loadBlur(this, backdropUrls.get(0), mBinding.globalBlurBg);
        } else {
            String initialPic = !TextUtils.isEmpty(getWallPic()) ? getWallPic() : getPic();
            if (!TextUtils.isEmpty(initialPic)) {
                mBackdropAdapter.setItems(Collections.singletonList(initialPic));
                BlurUtil.loadBlur(this, initialPic, mBinding.globalBlurBg);
            }
        }

        // 2. 标题（片名）
        if (!TextUtils.isEmpty(detail.getDisplayTitle())) {
            mBinding.titleText.setText(detail.getDisplayTitle());
        }
        if (!TextUtils.isEmpty(detail.getOriginalTitle()) && !detail.getOriginalTitle().equalsIgnoreCase(detail.getDisplayTitle())) {
            mBinding.originalTitleText.setVisibility(View.VISIBLE);
            mBinding.originalTitleText.setText(detail.getOriginalTitle());
        } else {
            mBinding.originalTitleText.setVisibility(View.GONE);
        }

        // 评分徽章（对齐截图2：★ 8.2）
        if (detail.getVoteAverage() > 0) {
            mBinding.ratingBadge.setVisibility(View.VISIBLE);
            mBinding.ratingText.setText(String.format(java.util.Locale.US, "%.1f", detail.getVoteAverage()));
        } else {
            mBinding.ratingBadge.setVisibility(View.GONE);
        }

        // 3. 元信息（左对齐，格式如：2026-10-15 · 喜剧, 剧情 · 0 min）
        List<String> metaParts = new ArrayList<>();
        if (!TextUtils.isEmpty(detail.getDate())) {
            metaParts.add(detail.getDate());
        } else if (!TextUtils.isEmpty(detail.getYear())) {
            metaParts.add(detail.getYear());
        }
        if (detail.getGenres() != null && !detail.getGenres().isEmpty()) {
            List<String> genreNames = new ArrayList<>();
            for (TmdbDetail.Genre g : detail.getGenres()) {
                genreNames.add(g.name);
            }
            metaParts.add(TextUtils.join(", ", genreNames));
        }
        metaParts.add(detail.getRuntimeMinutes() + " min");
        if (!metaParts.isEmpty()) {
            mBinding.metaText.setText(TextUtils.join(" · ", metaParts));
        }

        // 4. 剧情简介（3行折叠，点击展开/收起）
        if (!TextUtils.isEmpty(detail.getOverview())) {
            mBinding.overviewText.setText(detail.getOverview());
        }
        adjustHeroToFitFirstScreen();

        // 5. 演职人员
        if (detail.getCredits() != null && detail.getCredits().cast != null && !detail.getCredits().cast.isEmpty()) {
            mBinding.castSectionBox.setVisibility(View.VISIBLE);
            mCastAdapter.addAll(detail.getCredits().cast);
        } else {
            mBinding.castSectionBox.setVisibility(View.GONE);
        }

        // 6. 主创团队
        List<TmdbDetail.CrewMember> keyCrew = detail.getKeyCrew();
        if (!keyCrew.isEmpty()) {
            mBinding.crewSectionBox.setVisibility(View.VISIBLE);
            mCrewAdapter.addAll(keyCrew);
        } else {
            mBinding.crewSectionBox.setVisibility(View.GONE);
        }

        // 7. 猜你喜欢推荐
        List<TmdbMedia> recommends = detail.getRecommendations();
        if (!recommends.isEmpty()) {
            mBinding.recommendSectionBox.setVisibility(View.VISIBLE);
            mRecommendAdapter.addAll(recommends);
        } else {
            mBinding.recommendSectionBox.setVisibility(View.GONE);
        }
    }

    private void startPlayback(Episode episode) {
        if (episode != null && mCurrentFlag != null) {
            History history = History.find(getHistoryKey());
            if (history == null) {
                history = new History();
                history.setKey(getHistoryKey());
                history.setVodName(getName());
                history.setVodPic(getPic());
            }
            history.setVodFlag(mCurrentFlag.getFlag());
            history.setEpisodeUrl(episode.getUrl());
            history.setVodRemarks(episode.getName());
            history.save(VodConfig.getCid());
        }

        VideoActivity.start(this, getKey(), getId(), getName(), getPic(), getMark(), isCollect(), getWallPic(), getContent());
    }

    private void initViewPager() {
        mBackdropAdapter = new TmdbBackdropAdapter();
        mBinding.backdropPager.setAdapter(mBackdropAdapter);
        mBinding.backdropPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (mBackdropAdapter != null && position >= 0 && position < mBackdropAdapter.getItemCount()) {
                    BlurUtil.loadBlur(VodDetailActivity.this, mBackdropAdapter.getItems().get(position), mBinding.globalBlurBg);
                }
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    stopAutoSlider();
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    startAutoSlider();
                }
            }
        });
        mSliderRunnable = new Runnable() {
            @Override
            public void run() {
                if (mBackdropAdapter != null && mBackdropAdapter.getItemCount() > 1 && mBinding != null) {
                    int next = (mBinding.backdropPager.getCurrentItem() + 1) % mBackdropAdapter.getItemCount();
                    mBinding.backdropPager.setCurrentItem(next, true);
                    mSliderHandler.postDelayed(this, 5000);
                }
            }
        };
    }

    private void startAutoSlider() {
        stopAutoSlider();
        if (mBackdropAdapter != null && mBackdropAdapter.getItemCount() > 1 && mSliderRunnable != null) {
            mSliderHandler.postDelayed(mSliderRunnable, 5000);
        }
    }

    private void stopAutoSlider() {
        if (mSliderRunnable != null) {
            mSliderHandler.removeCallbacks(mSliderRunnable);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startAutoSlider();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoSlider();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAutoSlider();
    }
}
