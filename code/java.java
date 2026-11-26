package com.exteraplugins.participants_channels;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ManageChatUserCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AudioPlayerAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScrollSlidingTextTabStrip;
import org.telegram.ui.Components.SharedMediaLayout;
import org.telegram.ui.Components.StickerEmptyView;
import org.telegram.ui.ProfileActivity;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Main {

    private static final String TAG = "ParticipantsChannels";
    public static final int TAB_ID = 1077;
    private static final String PREFS_NAME = "participants_channels_prefs";

    private static final List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();
    private static volatile boolean loaded = false;

    // Config settings
    private static volatile boolean loadFromHistory = true;
    private static volatile boolean onlyIfHidden = false;
    private static volatile int historyLimit = 300;
    private static volatile String lang = "ru";

    // Discovered channels per chat (dialogId -> list of entries)
    private static final Map<Long, List<ChannelEntry>> chatChannels = new ConcurrentHashMap<>();
    // Global user -> personal channel mapping cache
    private static final Map<Long, Long> userChannelCache = new ConcurrentHashMap<>();
    // In-memory negative cache with TTL (uid -> expireTimeMs)
    private static final Map<Long, Long> noChannelUntil = new ConcurrentHashMap<>();
    // Chats currently undergoing active scan
    private static final Set<Long> scanningChats = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
    // Scan timestamps to avoid scanning too frequently
    private static final Map<Long, Long> lastScanTime = new ConcurrentHashMap<>();

    // Weak map of attached custom views for each SharedMediaLayout
    private static final WeakHashMap<SharedMediaLayout, ChannelsLayout> layouts = new WeakHashMap<>();
    private static final WeakHashMap<SharedMediaLayout, ProfileActivity> smlToProfile = new WeakHashMap<>();
    private static final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();

    // Reflection caches
    private static Field smlDialogIdField;
    private static Field smlProfileActivityField;
    private static Field paMediaCounterField;
    private static Method paSetMediaCounterTextMethod;
    private static boolean paSetMediaCounterTextChecked = false;
    private static Field tabStripDelegateField;
    private static Field smlMediaPagesField;

    private static final Set<Class<?>> hookedDelegateClasses = Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    public static class ChannelEntry {
        public long channelId;
        public TLRPC.Chat chat;
        public final List<Long> ownerUids = new CopyOnWriteArrayList<>();
        public int subscribersCount;
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    public static void load() {
        if (loaded) return;
        loaded = true;
        loadGlobalCache();
        try {
            unhooks.addAll(XposedBridge.hookAllMethods(SharedMediaLayout.class, "updateTabs", updateTabsHook));
            unhooks.addAll(XposedBridge.hookAllMethods(SharedMediaLayout.class, "getCurrentListView", getCurrentListViewHook));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook SharedMediaLayout methods", t);
        }
        try {
            unhooks.addAll(XposedBridge.hookAllMethods(ScrollSlidingTextTabStrip.class, "getNextPageId", getNextPageIdHook));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ScrollSlidingTextTabStrip methods", t);
        }
        try {
            unhooks.addAll(XposedBridge.hookAllMethods(ProfileActivity.class, "updateSelectedMediaTabText", updateCounterHook));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook ProfileActivity methods", t);
        }
        Log.d(TAG, "Loaded successfully");
    }

    public static void unload() {
        for (XC_MethodHook.Unhook unhook : unhooks) {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {}
        }
        unhooks.clear();
        hookedDelegateClasses.clear();
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<SharedMediaLayout, ChannelsLayout> entry : layouts.entrySet()) {
                    SharedMediaLayout sml = entry.getKey();
                    ChannelsLayout layout = entry.getValue();
                    if (layout != null && layout.getParent() instanceof ViewGroup) {
                        ((ViewGroup) layout.getParent()).removeView(layout);
                    }
                    if (sml != null) {
                        sml.disableScroll(false);
                        if (sml.scrollSlidingTextTabStrip != null) {
                            unwrapDelegate(sml.scrollSlidingTextTabStrip);
                        }
                        showMediaPages(sml);
                    }
                }
                layouts.clear();
            }
        });
        saveGlobalCache();
        loaded = false;
        Log.d(TAG, "Unloaded successfully");
    }

    public static void applySettings(String jsonStr) {
        if (TextUtils.isEmpty(jsonStr)) return;
        try {
            JSONObject obj = new JSONObject(jsonStr);
            if (obj.has("lang")) lang = obj.getString("lang");
            if (obj.has("load_from_history")) loadFromHistory = obj.getBoolean("load_from_history");
            if (obj.has("only_if_hidden")) onlyIfHidden = obj.getBoolean("only_if_hidden");
            if (obj.has("history_limit")) historyLimit = obj.getInt("history_limit");
            notifyAllTabsUpdated();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to apply settings", t);
        }
    }

    // ============================================================
    // Xposed Hooks & Tab Delegate Interception
    // ============================================================

    private static final XC_MethodHook updateTabsHook = new XC_MethodHook() {
        @Override
        public void afterHookedMethod(MethodHookParam param) {
            try {
                SharedMediaLayout sml = (SharedMediaLayout) param.thisObject;
                long dialogId = getDialogId(sml);
                if (dialogId >= 0) return; // Ignore private chats

                int account = getAccount(sml);
                MessagesController mc = MessagesController.getInstance(account);
                TLRPC.Chat chat = mc.getChat(-dialogId);
                if (chat == null) return;

                boolean isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
                boolean hasAdminInChannel = chat.creator || ChatObject.hasAdminRights(chat) || chat.admin_rights != null;

                if (isChannel && !hasAdminInChannel) {
                    return;
                }

                ScrollSlidingTextTabStrip tabStrip = sml.scrollSlidingTextTabStrip;
                if (tabStrip != null) {
                    wrapDelegateIfNeeded(tabStrip, sml);
                    if (!tabStrip.hasTab(TAB_ID)) {
                        tabStrip.addTextTab(TAB_ID, getTabTitle(dialogId));
                        tabStrip.finishAddingTabs();
                    }
                    if (isOurTabActive(sml)) {
                        tabStrip.selectTabWithId(TAB_ID, 1.0f);
                        BaseFragment pa = getProfileActivity(sml);
                        if (pa instanceof ProfileActivity && isOurTabActive(sml)) {
                            updateProfileCounter((ProfileActivity) pa, sml, dialogId);
                        }
                    }
                }

                ensureScanStarted(dialogId, account);
            } catch (Throwable t) {
                Log.e(TAG, "Error in updateTabs hook", t);
            }
        }
    };

    private static boolean isOurTabActive(SharedMediaLayout sml) {
        if (sml == null) return false;
        ChannelsLayout layout = layouts.get(sml);
        if (layout != null && layout.getVisibility() == View.VISIBLE) {
            return true;
        }
        return sml.scrollSlidingTextTabStrip != null && sml.scrollSlidingTextTabStrip.getCurrentTabId() == TAB_ID;
    }

    private static final XC_MethodHook getCurrentListViewHook = new XC_MethodHook() {
        @Override
        public void beforeHookedMethod(MethodHookParam param) {
            try {
                SharedMediaLayout sml = (SharedMediaLayout) param.thisObject;
                if (isOurTabActive(sml)) {
                    ChannelsLayout layout = layouts.get(sml);
                    if (layout != null) {
                        param.setResult(layout.listView);
                    }
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook getNextPageIdHook = new XC_MethodHook() {
        @Override
        public void afterHookedMethod(MethodHookParam param) {
            try {
                ScrollSlidingTextTabStrip strip = (ScrollSlidingTextTabStrip) param.thisObject;
                if (strip.getCurrentTabId() == TAB_ID) {
                    param.setResult(-1);
                } else {
                    Object res = param.getResult();
                    if (res instanceof Integer && ((Integer) res) == TAB_ID) {
                        param.setResult(-1);
                    }
                }
            } catch (Throwable ignored) {}
        }
    };

    private static final XC_MethodHook updateCounterHook = new XC_MethodHook() {
        @Override
        public void beforeHookedMethod(MethodHookParam param) {
            try {
                ProfileActivity pa = (ProfileActivity) param.thisObject;
                SharedMediaLayout sml = pa.sharedMediaLayout;
                if (sml != null) {
                    smlToProfile.put(sml, pa);
                }
                if (isOurTabActive(sml)) {
                    long dialogId = getDialogId(sml);
                    updateProfileCounter(pa, sml, dialogId);
                    param.setResult(null);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in updateCounterHook before", t);
            }
        }

        @Override
        public void afterHookedMethod(MethodHookParam param) {
            try {
                ProfileActivity pa = (ProfileActivity) param.thisObject;
                SharedMediaLayout sml = pa.sharedMediaLayout;
                if (sml != null) {
                    smlToProfile.put(sml, pa);
                }
                if (isOurTabActive(sml)) {
                    long dialogId = getDialogId(sml);
                    updateProfileCounter(pa, sml, dialogId);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in updateCounterHook after", t);
            }
        }
    };

    public static class DelegateWrapper implements ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate {
        private final ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate original;
        private final WeakReference<SharedMediaLayout> smlRef;

        public DelegateWrapper(ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate original, SharedMediaLayout sml) {
            this.original = original;
            this.smlRef = new WeakReference<>(sml);
        }

        public ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate getOriginal() {
            return original;
        }

        @Override
        public void onPageSelected(int id, boolean forward) {
            SharedMediaLayout sml = smlRef.get();
            if (sml == null) {
                if (original != null) original.onPageSelected(id, forward);
                return;
            }

            if (id == TAB_ID) {
                if (sml.scrollSlidingTextTabStrip != null) {
                    sml.scrollSlidingTextTabStrip.selectTabWithId(TAB_ID, 1.0f);
                }
                onOurTabSelected(sml);
            } else {
                onOtherTabSelected(sml, id);
                if (original != null) {
                    original.onPageSelected(id, forward);
                }
            }
        }

        @Override
        public void onPageScrolled(float progress) {
            if (original != null) {
                original.onPageScrolled(progress);
            }
        }

        @Override
        public void onSamePageSelected() {
            SharedMediaLayout sml = smlRef.get();
            if (sml != null && sml.scrollSlidingTextTabStrip != null && sml.scrollSlidingTextTabStrip.getCurrentTabId() == TAB_ID) {
                ChannelsLayout layout = layouts.get(sml);
                if (layout != null && layout.listView != null) {
                    layout.listView.smoothScrollToPosition(0);
                }
                BaseFragment pa = getProfileActivity(sml);
                if (pa instanceof ProfileActivity) {
                    ((ProfileActivity) pa).scrollToSharedMedia(true);
                }
                return;
            }
            if (original != null) {
                original.onSamePageSelected();
            }
        }

        @Override
        public boolean canReorder(int id) {
            if (id == TAB_ID) return false;
            return original != null && original.canReorder(id);
        }

        @Override
        public boolean showOptions(int id, View view) {
            if (id == TAB_ID) return false;
            return original != null && original.showOptions(id, view);
        }
    }

    private static void wrapDelegateIfNeeded(ScrollSlidingTextTabStrip tabStrip, SharedMediaLayout sml) {
        try {
            if (tabStripDelegateField == null) {
                tabStripDelegateField = ScrollSlidingTextTabStrip.class.getDeclaredField("delegate");
                tabStripDelegateField.setAccessible(true);
            }
            ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate current =
                (ScrollSlidingTextTabStrip.ScrollSlidingTabStripDelegate) tabStripDelegateField.get(tabStrip);
            if (current != null && !(current instanceof DelegateWrapper)) {
                DelegateWrapper wrapper = new DelegateWrapper(current, sml);
                tabStrip.setDelegate(wrapper);
                hookDelegateClass(current.getClass());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to wrap tabStrip delegate", t);
        }
    }

    private static void unwrapDelegate(ScrollSlidingTextTabStrip tabStrip) {
        try {
            if (tabStripDelegateField == null) {
                tabStripDelegateField = ScrollSlidingTextTabStrip.class.getDeclaredField("delegate");
                tabStripDelegateField.setAccessible(true);
            }
            Object current = tabStripDelegateField.get(tabStrip);
            if (current instanceof DelegateWrapper) {
                tabStrip.setDelegate(((DelegateWrapper) current).getOriginal());
            }
        } catch (Throwable ignored) {}
    }

    private static void hookDelegateClass(Class<?> cls) {
        if (!hookedDelegateClasses.add(cls)) return;
        try {
            unhooks.addAll(XposedBridge.hookAllMethods(cls, "onPageSelected", new XC_MethodHook() {
                @Override
                public void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                        if (((Integer) param.args[0]) == TAB_ID) {
                            param.setResult(null);
                        }
                    }
                }
            }));
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook delegate class onPageSelected", t);
        }
    }

    private static void onOurTabSelected(final SharedMediaLayout sml) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                try {
                    sml.disableScroll(true);

                    long dialogId = getDialogId(sml);
                    int account = getAccount(sml);
                    BaseFragment pa = getProfileActivity(sml);

                    ChannelsLayout layout = layouts.get(sml);
                    if (layout == null) {
                        layout = new ChannelsLayout(sml.getContext(), dialogId, account, pa);
                        layouts.put(sml, layout);
                        sml.addView(layout, LayoutHelper.createFrameMatchParent());
                    }

                    hideMediaPages(sml);

                    layout.setVisibility(View.VISIBLE);
                    layout.bringToFront();
                    if (sml.scrollSlidingTextTabStrip != null) {
                        sml.scrollSlidingTextTabStrip.selectTabWithId(TAB_ID, 1.0f);
                        sml.scrollSlidingTextTabStrip.bringToFront();
                    }
                    layout.updateData();

                    if (pa instanceof ProfileActivity) {
                        final ProfileActivity profile = (ProfileActivity) pa;
                        profile.scrollToSharedMedia(true);
                        updateProfileCounter(profile, sml, dialogId);
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                if (isOurTabActive(sml)) {
                                    if (sml.scrollSlidingTextTabStrip != null) {
                                        sml.scrollSlidingTextTabStrip.selectTabWithId(TAB_ID, 1.0f);
                                    }
                                    updateProfileCounter(profile, sml, dialogId);
                                }
                            }
                        }, 150);
                    }

                    ensureScanStarted(dialogId, account);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onOurTabSelected", t);
                }
            }
        });
    }

    private static void onOtherTabSelected(final SharedMediaLayout sml, final int newTabId) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                try {
                    sml.disableScroll(false);

                    ChannelsLayout layout = layouts.get(sml);
                    if (layout != null) {
                        layout.setVisibility(View.GONE);
                    }
                    showMediaPages(sml);

                    BaseFragment pa = getProfileActivity(sml);
                    if (pa instanceof ProfileActivity) {
                        ((ProfileActivity) pa).updateSelectedMediaTabText();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onOtherTabSelected", t);
                }
            }
        });
    }

    private static View[] getMediaPages(SharedMediaLayout sml) {
        try {
            if (smlMediaPagesField == null) {
                smlMediaPagesField = SharedMediaLayout.class.getDeclaredField("mediaPages");
                smlMediaPagesField.setAccessible(true);
            }
            return (View[]) smlMediaPagesField.get(sml);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hideMediaPages(SharedMediaLayout sml) {
        View[] pages = getMediaPages(sml);
        if (pages != null) {
            for (View p : pages) {
                if (p != null) {
                    p.setVisibility(View.INVISIBLE);
                }
            }
        }
    }

    private static void showMediaPages(SharedMediaLayout sml) {
        View[] pages = getMediaPages(sml);
        if (pages != null && pages.length > 0 && pages[0] != null) {
            pages[0].setVisibility(View.VISIBLE);
        }
    }

    // ============================================================
    // UI: ChannelsLayout
    // ============================================================

    public static class ChannelsLayout extends FrameLayout {
        public final long dialogId;
        public final int account;
        public final BaseFragment parentFragment;
        public final RecyclerListView listView;
        public final StickerEmptyView emptyView;
        public final ChannelsAdapter adapter;

        public ChannelsLayout(Context context, long dialogId, int account, BaseFragment parentFragment) {
            super(context);
            this.dialogId = dialogId;
            this.account = account;
            this.parentFragment = parentFragment;

            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            listView = new RecyclerListView(context);
            listView.setLayoutManager(new LinearLayoutManager(context));
            listView.setPadding(0, AndroidUtilities.dp(54), 0, AndroidUtilities.dp(48));
            listView.setClipToPadding(false);
            listView.setNestedScrollingEnabled(true);

            emptyView = new StickerEmptyView(context, null, 1);
            emptyView.setPadding(0, AndroidUtilities.dp(54), 0, 0);
            addView(emptyView, LayoutHelper.createFrameMatchParent());

            listView.setEmptyView(emptyView);

            adapter = new ChannelsAdapter(this);
            listView.setAdapter(adapter);

            listView.setOnItemClickListener(new RecyclerListView.OnItemClickListener() {
                @Override
                public void onItemClick(View view, int position) {
                    List<ChannelEntry> list = chatChannels.get(ChannelsLayout.this.dialogId);
                    if (list == null || position < 0 || position >= list.size()) return;
                    ChannelEntry entry = list.get(position);
                    if (entry == null || entry.chat == null) return;

                    Bundle args = new Bundle();
                    args.putLong("chat_id", entry.channelId);
                    if (ChannelsLayout.this.parentFragment != null) {
                        ChannelsLayout.this.parentFragment.presentFragment(new ChatActivity(args));
                    }
                }
            });

            listView.setOnItemLongClickListener(new RecyclerListView.OnItemLongClickListener() {
                @Override
                public boolean onItemClick(View view, int position) {
                    List<ChannelEntry> list = chatChannels.get(ChannelsLayout.this.dialogId);
                    if (list == null || position < 0 || position >= list.size()) return false;
                    ChannelEntry entry = list.get(position);
                    if (entry == null || entry.ownerUids.isEmpty()) return false;

                    if (entry.ownerUids.size() == 1) {
                        Bundle args = new Bundle();
                        args.putLong("user_id", entry.ownerUids.get(0));
                        if (ChannelsLayout.this.parentFragment != null) {
                            ChannelsLayout.this.parentFragment.presentFragment(new ProfileActivity(args));
                        }
                        return true;
                    }

                    // Multiple owners dialog
                    showOwnersDialog(entry);
                    return true;
                }
            });

            addView(listView, LayoutHelper.createFrameMatchParent());
            updateData();
        }

        private void showOwnersDialog(final ChannelEntry entry) {
            if (parentFragment == null || parentFragment.getParentActivity() == null) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(parentFragment.getParentActivity());
            builder.setTitle(get("channel_owners", "Channel owners"));

            MessagesController mc = MessagesController.getInstance(account);
            final List<CharSequence> names = new ArrayList<>();
            final List<Long> uids = new ArrayList<>();

            for (Long uid : entry.ownerUids) {
                TLRPC.User u = mc.getUser(uid);
                String name = u != null ? UserObject.getUserName(u) : String.valueOf(uid);
                names.add(name);
                uids.add(uid);
            }

            builder.setItems(names.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (which >= 0 && which < uids.size()) {
                        Bundle args = new Bundle();
                        args.putLong("user_id", uids.get(which));
                        parentFragment.presentFragment(new ProfileActivity(args));
                    }
                }
            });
            builder.setNegativeButton(get("close", "Close"), null);
            builder.show();
        }

        public void updateData() {
            boolean scanning = scanningChats.contains(dialogId);
            List<ChannelEntry> list = chatChannels.get(dialogId);
            int count = list != null ? list.size() : 0;

            if (scanning) {
                emptyView.title.setText(get("scanning_title", "Scanning chat members..."));
                emptyView.setSubtitle(get("scanning_sub", "Personal channels will appear here automatically"));
                emptyView.showProgress(true, true);
            } else {
                emptyView.title.setText(get("no_channels", "No channels found"));
                emptyView.setSubtitle(get("no_channels_sub", "Chat participants have not attached personal channels to their profiles"));
                emptyView.showProgress(false, true);
            }

            adapter.notifyDataSetChanged();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int parentHeight = 0;
            if (getParent() instanceof View) {
                parentHeight = ((View) getParent()).getMeasuredHeight();
            }
            if (parentHeight > 0) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(parentHeight, MeasureSpec.EXACTLY));
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        }
    }

    public static class ChannelsAdapter extends RecyclerListView.SelectionAdapter {
        private final ChannelsLayout layout;

        public ChannelsAdapter(ChannelsLayout layout) {
            this.layout = layout;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            List<ChannelEntry> list = chatChannels.get(layout.dialogId);
            return list != null ? list.size() : 0;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ManageChatUserCell cell = new ManageChatUserCell(parent.getContext(), 8, 6, false);
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            List<ChannelEntry> list = chatChannels.get(layout.dialogId);
            if (list == null || position < 0 || position >= list.size()) return;
            ChannelEntry entry = list.get(position);
            if (entry == null || entry.chat == null) return;

            ManageChatUserCell cell = (ManageChatUserCell) holder.itemView;
            String title = entry.chat.title;

            StringBuilder subtitle = new StringBuilder();
            if (entry.subscribersCount > 0) {
                subtitle.append(LocaleController.formatPluralString("Subscribers", entry.subscribersCount));
            } else {
                subtitle.append(get("channel", "Channel"));
            }

            if (!entry.ownerUids.isEmpty()) {
                MessagesController mc = MessagesController.getInstance(layout.account);
                if (entry.ownerUids.size() == 1) {
                    TLRPC.User owner = mc.getUser(entry.ownerUids.get(0));
                    String name = owner != null ? UserObject.getUserName(owner) : null;
                    if (!TextUtils.isEmpty(name)) {
                        subtitle.append(" • ").append(get("owner_prefix", "Owner: ")).append(name);
                    }
                } else {
                    subtitle.append(" • ").append(entry.ownerUids.size()).append(" ").append(get("owners", "owners"));
                }
            }

            boolean hasDivider = position < getItemCount() - 1;
            cell.setData(entry.chat, title, subtitle.toString(), hasDivider);
        }
    }

    // ============================================================
    // Fast Scanner Logic
    // ============================================================

    private static void ensureScanStarted(long dialogId, int account) {
        loadChatCache(dialogId);
        Long lastTime = lastScanTime.get(dialogId);
        long now = SystemClock.elapsedRealtime();
        if (lastTime != null && (now - lastTime) < 180000) { // 3 minutes cache
            return;
        }
        if (scanningChats.contains(dialogId)) {
            return;
        }
        startScan(dialogId, account);
    }

    private static void startScan(final long dialogId, final int account) {
        if (!scanningChats.add(dialogId)) return;
        lastScanTime.put(dialogId, SystemClock.elapsedRealtime());
        notifyChannelsUpdated(dialogId);

        scanExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    doScan(dialogId, account);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in doScan for dialog " + dialogId, t);
                } finally {
                    scanningChats.remove(dialogId);
                    notifyChannelsUpdated(dialogId);
                    saveChatCache(dialogId);
                }
            }
        });
    }

    private static void doScan(final long dialogId, final int account) {
        final MessagesController mc = MessagesController.getInstance(account);
        long chatId = -dialogId;
        TLRPC.Chat chat = mc.getChat(chatId);
        if (chat == null) return;

        long scanTargetId = chatId;
        boolean isChannel = ChatObject.isChannel(chat);

        if (isChannel && !chat.megagroup) {
            // Broadcast channel
            boolean hasAdmin = chat.creator || ChatObject.hasAdminRights(chat) || chat.admin_rights != null;
            if (!hasAdmin) {
                return;
            }
        }

        TLRPC.Chat targetChat = mc.getChat(scanTargetId);
        if (targetChat == null) return;
        boolean targetIsChannel = ChatObject.isChannel(targetChat);

        final Set<Long> processedUids = new HashSet<>();

        // Phase 1: Admins first!
        if (targetIsChannel) {
            final List<Long> adminUids = new ArrayList<>();
            final Object lock = new Object();
            TLRPC.TL_channels_getParticipants req = new TLRPC.TL_channels_getParticipants();
            req.channel = mc.getInputChannel(scanTargetId);
            req.filter = new TLRPC.TL_channelParticipantsAdmins();
            req.limit = 100;
            req.offset = 0;

            ConnectionsManager.getInstance(account).sendRequest(req, new RequestDelegate() {
                @Override
                public void run(TLObject response, TLRPC.TL_error error) {
                    if (response instanceof TLRPC.TL_channels_channelParticipants) {
                        TLRPC.TL_channels_channelParticipants cp = (TLRPC.TL_channels_channelParticipants) response;
                        for (int i = 0; i < cp.users.size(); i++) {
                            TLRPC.User u = cp.users.get(i);
                            mc.putUser(u, false);
                            adminUids.add(u.id);
                        }
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            });

            synchronized (lock) {
                try {
                    lock.wait(5000);
                } catch (InterruptedException ignored) {}
            }

            if (!adminUids.isEmpty()) {
                processedUids.addAll(adminUids);
                checkUserIds(dialogId, account, adminUids);
            }
        } else {
            // Regular chat participants
            TLRPC.ChatFull cf = mc.getChatFull(scanTargetId);
            if (cf != null && cf.participants != null && cf.participants.participants != null) {
                List<Long> regularUids = new ArrayList<>();
                for (int i = 0; i < cf.participants.participants.size(); i++) {
                    regularUids.add(cf.participants.participants.get(i).user_id);
                }
                processedUids.addAll(regularUids);
                checkUserIds(dialogId, account, regularUids);
                return;
            }
        }

        // Phase 2: History Search (active chatters first, if enabled)
        if (loadFromHistory) {
            boolean shouldSearchHistory = true;
            if (onlyIfHidden) {
                TLRPC.ChatFull cf = mc.getChatFull(scanTargetId);
                shouldSearchHistory = cf != null && cf.participants_hidden;
            }
            if (shouldSearchHistory) {
                scanHistory(dialogId, scanTargetId, account, processedUids);
            }
        }

        // Phase 3: Recent Members (pages of 200, up to 1000 members)
        int offset = 0;
        int maxPages = 5;
        for (int page = 0; page < maxPages; page++) {
            if (!loaded) break;
            final List<Long> pageUids = new ArrayList<>();
            final Object pageLock = new Object();
            final boolean[] endReached = new boolean[]{false};

            TLRPC.TL_channels_getParticipants req = new TLRPC.TL_channels_getParticipants();
            req.channel = mc.getInputChannel(scanTargetId);
            try {
                req.filter = (TLRPC.ChannelParticipantsFilter) Class.forName("org.telegram.tgnet.TLRPC$TL_channelParticipantsRecent").getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                req.filter = null;
            }
            req.limit = 200;
            req.offset = offset;

            ConnectionsManager.getInstance(account).sendRequest(req, new RequestDelegate() {
                @Override
                public void run(TLObject response, TLRPC.TL_error error) {
                    if (response instanceof TLRPC.TL_channels_channelParticipants) {
                        TLRPC.TL_channels_channelParticipants cp = (TLRPC.TL_channels_channelParticipants) response;
                        for (int i = 0; i < cp.users.size(); i++) {
                            TLRPC.User u = cp.users.get(i);
                            mc.putUser(u, false);
                            pageUids.add(u.id);
                        }
                        if (cp.users.size() < 200) {
                            endReached[0] = true;
                        }
                    } else {
                        endReached[0] = true;
                    }
                    synchronized (pageLock) {
                        pageLock.notifyAll();
                    }
                }
            });

            synchronized (pageLock) {
                try {
                    pageLock.wait(6000);
                } catch (InterruptedException ignored) {}
            }

            if (pageUids.isEmpty() || endReached[0]) {
                if (!pageUids.isEmpty()) {
                    List<Long> toCheck = new ArrayList<>();
                    for (Long uid : pageUids) {
                        if (processedUids.add(uid)) toCheck.add(uid);
                    }
                    checkUserIds(dialogId, account, toCheck);
                }
                break;
            }

            List<Long> toCheck = new ArrayList<>();
            for (Long uid : pageUids) {
                if (processedUids.add(uid)) toCheck.add(uid);
            }
            checkUserIds(dialogId, account, toCheck);
            offset += 200;
        }
    }

    private static void scanHistory(final long dialogId, final long chatId, final int account, final Set<Long> processedUids) {
        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Chat chat = mc.getChat(chatId);
        if (chat == null) return;

        int remaining = Math.min(historyLimit, 2000);
        int offsetId = 0;

        while (remaining > 0) {
            int batch = Math.min(remaining, 100);
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            if (ChatObject.isChannel(chat)) {
                TLRPC.TL_inputPeerChannel inp = new TLRPC.TL_inputPeerChannel();
                inp.channel_id = chatId;
                inp.access_hash = chat.access_hash;
                req.peer = inp;
            } else {
                TLRPC.TL_inputPeerChat inp = new TLRPC.TL_inputPeerChat();
                inp.chat_id = chatId;
                req.peer = inp;
            }
            req.limit = batch;
            req.offset_id = offsetId;

            final Object histLock = new Object();
            final List<Long> histUids = new ArrayList<>();
            final int[] lastId = new int[]{0};
            final int[] msgCount = new int[]{0};

            ConnectionsManager.getInstance(account).sendRequest(req, new RequestDelegate() {
                @Override
                public void run(TLObject response, TLRPC.TL_error error) {
                    if (response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages msgs = (TLRPC.messages_Messages) response;
                        for (int i = 0; i < msgs.users.size(); i++) {
                            TLRPC.User u = msgs.users.get(i);
                            mc.putUser(u, false);
                        }
                        for (int i = 0; i < msgs.messages.size(); i++) {
                            TLRPC.Message m = msgs.messages.get(i);
                            lastId[0] = m.id;
                            if (m.from_id instanceof TLRPC.TL_peerUser) {
                                histUids.add(((TLRPC.TL_peerUser) m.from_id).user_id);
                            }
                        }
                        msgCount[0] = msgs.messages.size();
                    }
                    synchronized (histLock) {
                        histLock.notifyAll();
                    }
                }
            });

            synchronized (histLock) {
                try {
                    histLock.wait(6000);
                } catch (InterruptedException ignored) {}
            }

            if (!histUids.isEmpty()) {
                List<Long> toCheck = new ArrayList<>();
                for (Long uid : histUids) {
                    if (processedUids.add(uid)) toCheck.add(uid);
                }
                checkUserIds(dialogId, account, toCheck);
            }

            if (msgCount[0] < batch || lastId[0] == 0) {
                break;
            }
            offsetId = lastId[0];
            remaining -= batch;
        }
    }

    private static void checkUserIds(final long dialogId, final int account, List<Long> uids) {
        if (uids == null || uids.isEmpty()) return;
        final MessagesController mc = MessagesController.getInstance(account);
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        List<Long> needFetch = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();

        for (Long uid : uids) {
            if (uid == null || uid <= 0) continue;
            Long until = noChannelUntil.get(uid);
            if (until != null && now < until) continue;

            // In-memory check: bot, deleted, support
            TLRPC.User user = mc.getUser(uid);
            if (user != null && (user.bot || user.deleted || user.support)) {
                noChannelUntil.put(uid, now + 86400_000L); // 24h for bots
                continue;
            }

            // In-memory userChannelCache
            Long cachedChannelId = userChannelCache.get(uid);
            if (cachedChannelId != null) {
                addDiscoveredChannel(dialogId, uid, cachedChannelId, account);
                continue;
            }

            // In-memory MessagesController UserFull
            TLRPC.UserFull uf = mc.getUserFull(uid);
            if (uf != null) {
                if (uf.personal_channel_id > 0) {
                    userChannelCache.put(uid, uf.personal_channel_id);
                    addDiscoveredChannel(dialogId, uid, uf.personal_channel_id, account);
                } else {
                    noChannelUntil.put(uid, now + 1800_000L); // 30m
                }
                continue;
            }

            needFetch.add(uid);
        }

        if (needFetch.isEmpty()) return;

        // Controlled concurrency: 2 concurrent requests with adaptive spacing
        final int concurrency = 2;
        final List<Long> queue = Collections.synchronizedList(new ArrayList<>(needFetch));
        final Object doneLock = new Object();
        final AtomicInteger inflight = new AtomicInteger(0);
        final AtomicLong floodWaitUntil = new AtomicLong(0);
        final AtomicInteger dispatchDelayMs = new AtomicInteger(150);

        while ((!queue.isEmpty() || inflight.get() > 0) && loaded) {
            long currentNow = SystemClock.elapsedRealtime();
            long waitFlood = floodWaitUntil.get() - currentNow;
            if (waitFlood > 0) {
                if (waitFlood > 45_000L) {
                    Log.w(TAG, "FLOOD_WAIT too long (" + waitFlood + "ms), stopping member check");
                    break;
                }
                try {
                    Thread.sleep(Math.min(waitFlood, 35_000L));
                } catch (InterruptedException ignored) {}
                continue;
            }

            while (inflight.get() < concurrency && !queue.isEmpty() && loaded) {
                if (floodWaitUntil.get() > SystemClock.elapsedRealtime()) {
                    break;
                }
                final long uid = queue.remove(0);

                TLRPC.InputUser inputUser = mc.getInputUser(uid);
                if (inputUser == null || inputUser.user_id == 0) {
                    TLRPC.User u = mc.getUser(uid);
                    if (u == null) {
                        u = storage.getUser(uid);
                        if (u != null) {
                            mc.putUser(u, true);
                            inputUser = mc.getInputUser(u);
                        }
                    }
                }
                if (inputUser == null || inputUser.user_id == 0) {
                    // Cannot query without access hash, skip without poisoning cache
                    continue;
                }

                inflight.incrementAndGet();

                TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
                req.id = inputUser;

                ConnectionsManager.getInstance(account).sendRequest(req, new RequestDelegate() {
                    @Override
                    public void run(TLObject response, TLRPC.TL_error error) {
                        try {
                            if (response instanceof TLRPC.TL_users_userFull) {
                                TLRPC.TL_users_userFull ufull = (TLRPC.TL_users_userFull) response;
                                if (ufull.chats != null) {
                                    for (int i = 0; i < ufull.chats.size(); i++) {
                                        mc.putChat(ufull.chats.get(i), false);
                                    }
                                }
                                long pcid = ufull.full_user != null ? ufull.full_user.personal_channel_id : 0;
                                if (pcid > 0) {
                                    userChannelCache.put(uid, pcid);
                                    addDiscoveredChannel(dialogId, uid, pcid, account);
                                } else {
                                    noChannelUntil.put(uid, SystemClock.elapsedRealtime() + 1800_000L);
                                }
                            } else if (error != null) {
                                String text = error.text != null ? error.text : "";
                                if (text.startsWith("FLOOD_WAIT_")) {
                                    try {
                                        int sec = Integer.parseInt(text.replace("FLOOD_WAIT_", ""));
                                        floodWaitUntil.set(SystemClock.elapsedRealtime() + (sec + 1) * 1000L);
                                        dispatchDelayMs.set(Math.min(dispatchDelayMs.get() + 150, 600));
                                        queue.add(uid);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "Error processing user " + uid, t);
                        } finally {
                            inflight.decrementAndGet();
                            synchronized (doneLock) {
                                doneLock.notifyAll();
                            }
                        }
                    }
                });

                // Gentle adaptive spacing between request dispatches
                try {
                    Thread.sleep(dispatchDelayMs.get());
                } catch (InterruptedException ignored) {}
            }

            synchronized (doneLock) {
                try {
                    doneLock.wait(200);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private static synchronized void addDiscoveredChannel(final long dialogId, final long uid, final long channelId, final int account) {
        if (channelId == 0) return;
        List<ChannelEntry> list = chatChannels.get(dialogId);
        if (list == null) {
            list = new CopyOnWriteArrayList<>();
            chatChannels.put(dialogId, list);
        }

        for (ChannelEntry entry : list) {
            if (entry.channelId == channelId) {
                if (!entry.ownerUids.contains(uid)) {
                    entry.ownerUids.add(uid);
                    notifyChannelsUpdated(dialogId);
                }
                return;
            }
        }

        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Chat chat = mc.getChat(channelId);
        if (chat == null) {
            chat = MessagesStorage.getInstance(account).getChat(channelId);
            if (chat != null) {
                mc.putChat(chat, true);
            }
        }

        if (chat == null) {
            // Channel not in memory or storage, request UserFull for uid to get channel object with access_hash
            TLRPC.InputUser inputUser = mc.getInputUser(uid);
            if (inputUser != null && inputUser.user_id != 0) {
                TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
                req.id = inputUser;
                ConnectionsManager.getInstance(account).sendRequest(req, new RequestDelegate() {
                    @Override
                    public void run(TLObject response, TLRPC.TL_error error) {
                        if (response instanceof TLRPC.TL_users_userFull) {
                            TLRPC.TL_users_userFull ufull = (TLRPC.TL_users_userFull) response;
                            if (ufull.chats != null) {
                                for (int i = 0; i < ufull.chats.size(); i++) {
                                    mc.putChat(ufull.chats.get(i), false);
                                }
                            }
                            addDiscoveredChannel(dialogId, uid, channelId, account);
                        }
                    }
                });
            }
            return;
        }

        ChannelEntry entry = new ChannelEntry();
        entry.channelId = channelId;
        entry.chat = chat;
        entry.ownerUids.add(uid);
        entry.subscribersCount = chat.participants_count;
        list.add(entry);

        // Sort descending by subscribers
        Collections.sort(list, (a, b) -> Integer.compare(b.subscribersCount, a.subscribersCount));

        notifyChannelsUpdated(dialogId);
        saveChatCache(dialogId);
    }

    public static void notifyChannelsUpdated(final long dialogId) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<SharedMediaLayout, ChannelsLayout> entry : layouts.entrySet()) {
                    SharedMediaLayout sml = entry.getKey();
                    ChannelsLayout layout = entry.getValue();
                    if (sml != null && layout != null && layout.dialogId == dialogId) {
                        layout.updateData();
                        updateTabText(sml, dialogId);
                        BaseFragment pa = getProfileActivity(sml);
                        if (pa instanceof ProfileActivity && isOurTabActive(sml)) {
                            updateProfileCounter((ProfileActivity) pa, sml, dialogId);
                        }
                    }
                }
            }
        });
    }

    private static void notifyAllTabsUpdated() {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<SharedMediaLayout, ChannelsLayout> entry : layouts.entrySet()) {
                    SharedMediaLayout sml = entry.getKey();
                    ChannelsLayout layout = entry.getValue();
                    if (sml != null && layout != null) {
                        layout.updateData();
                        updateTabText(sml, layout.dialogId);
                    }
                }
            }
        });
    }

    // ============================================================
    // Helpers & Cache
    // ============================================================

    private static void updateTabText(SharedMediaLayout sml, long dialogId) {
        if (sml == null || sml.scrollSlidingTextTabStrip == null) return;
        ScrollSlidingTextTabStrip tabStrip = sml.scrollSlidingTextTabStrip;
        ArrayList<Integer> ids = tabStrip.getTabIds();
        if (ids == null) return;
        int idx = ids.indexOf(TAB_ID);
        if (idx >= 0 && idx < tabStrip.getTabsContainer().getChildCount()) {
            View child = tabStrip.getTabsContainer().getChildAt(idx);
            if (child instanceof TextView) {
                ((TextView) child).setText(getTabTitle(dialogId));
            }
        }
    }

    public static String formatChannelsCount(int count) {
        String l = lang;
        if (l == null) l = "ru";
        boolean isRu = "ru".equalsIgnoreCase(l);
        if (isRu) {
            int rem100 = count % 100;
            int rem10 = count % 10;
            String word;
            if (rem100 >= 11 && rem100 <= 19) {
                word = get("channels_count_many", "каналов");
            } else if (rem10 == 1) {
                word = get("channels_count_one", "канал");
            } else if (rem10 >= 2 && rem10 <= 4) {
                word = get("channels_count_few", "канала");
            } else {
                word = get("channels_count_many", "каналов");
            }
            return count + " " + word;
        } else {
            String word = count == 1 ? get("channels_count_one", "channel") : get("channels_count_many", "channels");
            return count + " " + word;
        }
    }

    private static void updateProfileCounter(ProfileActivity pa, SharedMediaLayout sml, long dialogId) {
        if (pa == null) return;

        boolean scanning = scanningChats.contains(dialogId);
        List<ChannelEntry> list = chatChannels.get(dialogId);
        int count = list != null ? list.size() : 0;

        String counterText;
        if (scanning) {
            if (count > 0) {
                counterText = formatChannelsCount(count) + " (" + get("scanning", "Scanning...") + ")";
            } else {
                counterText = get("tab_label", "Channels") + " (" + get("scanning", "Scanning...") + ")";
            }
        } else if (count > 0) {
            counterText = formatChannelsCount(count);
        } else {
            counterText = get("tab_label", "Channels");
        }

        if (!paSetMediaCounterTextChecked) {
            paSetMediaCounterTextChecked = true;
            try {
                paSetMediaCounterTextMethod = ProfileActivity.class.getDeclaredMethod("setMediaCounterText", CharSequence.class);
                paSetMediaCounterTextMethod.setAccessible(true);
            } catch (Throwable ignored) {
                paSetMediaCounterTextMethod = null;
            }
        }

        if (paSetMediaCounterTextMethod != null) {
            try {
                paSetMediaCounterTextMethod.invoke(pa, counterText);
                return;
            } catch (Throwable t) {
                Log.e(TAG, "Failed to invoke setMediaCounterText", t);
            }
        }

        AudioPlayerAlert.ClippingTextViewSwitcher counterView = getMediaCounterTextView(pa);
        if (counterView != null) {
            counterView.setText(counterText, false);
        }
    }

    private static String getTabTitle(long dialogId) {
        return get("tab_label", "Channels");
    }

    private static long getDialogId(SharedMediaLayout sml) {
        try {
            if (smlDialogIdField == null) {
                smlDialogIdField = SharedMediaLayout.class.getDeclaredField("dialog_id");
                smlDialogIdField.setAccessible(true);
            }
            return smlDialogIdField.getLong(sml);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int getAccount(SharedMediaLayout sml) {
        BaseFragment pa = getProfileActivity(sml);
        if (pa != null) return pa.getCurrentAccount();
        return UserConfig.selectedAccount;
    }

    private static BaseFragment getProfileActivity(SharedMediaLayout sml) {
        if (sml == null) return null;
        ProfileActivity mapped = smlToProfile.get(sml);
        if (mapped != null) return mapped;
        try {
            if (smlProfileActivityField == null) {
                smlProfileActivityField = SharedMediaLayout.class.getDeclaredField("profileActivity");
                smlProfileActivityField.setAccessible(true);
            }
            return (BaseFragment) smlProfileActivityField.get(sml);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get profileActivity", t);
            return null;
        }
    }

    private static AudioPlayerAlert.ClippingTextViewSwitcher getMediaCounterTextView(ProfileActivity pa) {
        if (pa == null) return null;
        try {
            if (paMediaCounterField == null) {
                paMediaCounterField = ProfileActivity.class.getDeclaredField("mediaCounterTextView");
                paMediaCounterField.setAccessible(true);
            }
            return (AudioPlayerAlert.ClippingTextViewSwitcher) paMediaCounterField.get(pa);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get mediaCounterTextView", t);
            return null;
        }
    }

    private static SharedPreferences getPrefs() {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx != null ? ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) : null;
    }

    private static void loadGlobalCache() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        try {
            // Clean up old poisonous no_channels from disk
            if (prefs.contains("no_channels")) {
                prefs.edit().remove("no_channels").apply();
            }
            String ucStr = prefs.getString("user_channels", null);
            if (ucStr != null) {
                JSONObject obj = new JSONObject(ucStr);
                JSONArray names = obj.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String k = names.getString(i);
                        userChannelCache.put(Long.parseLong(k), obj.getLong(k));
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load global cache", t);
        }
    }

    private static void saveGlobalCache() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        try {
            JSONObject ucObj = new JSONObject();
            int count = 0;
            for (Map.Entry<Long, Long> entry : userChannelCache.entrySet()) {
                ucObj.put(String.valueOf(entry.getKey()), entry.getValue());
                if (++count > 5000) break;
            }
            prefs.edit()
                .putString("user_channels", ucObj.toString())
                .remove("no_channels")
                .apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save global cache", t);
        }
    }

    private static void loadChatCache(long dialogId) {
        if (chatChannels.containsKey(dialogId)) return;
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        try {
            String json = prefs.getString("chat_" + dialogId, null);
            if (json == null) return;

            JSONArray arr = new JSONArray(json);
            List<ChannelEntry> entries = new CopyOnWriteArrayList<>();
            int account = UserConfig.selectedAccount;
            MessagesController mc = MessagesController.getInstance(account);
            MessagesStorage storage = MessagesStorage.getInstance(account);

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                long cid = obj.getLong("cid");
                TLRPC.Chat chat = mc.getChat(cid);
                if (chat == null) {
                    chat = storage.getChat(cid);
                    if (chat != null) {
                        mc.putChat(chat, true);
                    }
                }
                if (chat == null && obj.has("title")) {
                    TLRPC.TL_channel fallbackChat = new TLRPC.TL_channel();
                    fallbackChat.id = cid;
                    fallbackChat.access_hash = obj.optLong("access_hash", 0);
                    fallbackChat.title = obj.optString("title", "");
                    fallbackChat.username = obj.optString("username", "");
                    fallbackChat.participants_count = obj.optInt("subs", 0);
                    chat = fallbackChat;
                    mc.putChat(chat, false);
                }
                if (chat != null) {
                    ChannelEntry e = new ChannelEntry();
                    e.channelId = cid;
                    e.chat = chat;
                    e.subscribersCount = obj.optInt("subs", chat.participants_count);
                    JSONArray owners = obj.optJSONArray("owners");
                    if (owners != null) {
                        for (int j = 0; j < owners.length(); j++) {
                            e.ownerUids.add(owners.getLong(j));
                        }
                    }
                    entries.add(e);
                }
            }
            if (!entries.isEmpty()) {
                chatChannels.put(dialogId, entries);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load chat cache for " + dialogId, t);
        }
    }

    private static void saveChatCache(long dialogId) {
        List<ChannelEntry> list = chatChannels.get(dialogId);
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return;
        try {
            if (list == null || list.isEmpty()) {
                if (!scanningChats.contains(dialogId)) {
                    prefs.edit().remove("chat_" + dialogId).apply();
                }
                return;
            }
            JSONArray arr = new JSONArray();
            for (ChannelEntry e : list) {
                if (e.chat == null) continue;
                JSONObject obj = new JSONObject();
                obj.put("cid", e.channelId);
                obj.put("subs", e.subscribersCount);
                obj.put("title", e.chat.title != null ? e.chat.title : "");
                obj.put("username", e.chat.username != null ? e.chat.username : "");
                obj.put("access_hash", e.chat.access_hash);
                JSONArray owners = new JSONArray();
                for (Long uid : e.ownerUids) {
                    owners.put(uid);
                }
                obj.put("owners", owners);
                arr.put(obj);
            }
            prefs.edit().putString("chat_" + dialogId, arr.toString()).apply();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save chat cache for " + dialogId, t);
        }
    }

    public static String get(String key, String fallback) {
        if ("ru".equals(lang)) {
            if ("tab_label".equals(key)) return "Каналы";
            if ("channels_count_one".equals(key)) return "канал";
            if ("channels_count_few".equals(key)) return "канала";
            if ("channels_count_many".equals(key)) return "каналов";
            if ("channel_owners".equals(key)) return "Владельцы канала";
            if ("close".equals(key)) return "Закрыть";
            if ("scanning".equals(key)) return "поиск...";
            if ("scanning_title".equals(key)) return "Идет поиск каналов участников...";
            if ("scanning_sub".equals(key)) return "Персональные каналы появятся здесь автоматически";
            if ("no_channels".equals(key)) return "Каналы участников не найдены";
            if ("no_channels_sub".equals(key)) return "У участников этого чата не привязаны персональные каналы в профиле";
            if ("owner_prefix".equals(key)) return "Владелец: ";
            if ("owners".equals(key)) return "владельцев";
            if ("channel".equals(key)) return "Канал";
        } else {
            if ("tab_label".equals(key)) return "Channels";
            if ("channels_count_one".equals(key)) return "channel";
            if ("channels_count_many".equals(key)) return "channels";
            if ("channel_owners".equals(key)) return "Channel owners";
            if ("close".equals(key)) return "Close";
            if ("scanning".equals(key)) return "scanning...";
            if ("scanning_title".equals(key)) return "Scanning chat members...";
            if ("scanning_sub".equals(key)) return "Personal channels will appear here automatically";
            if ("no_channels".equals(key)) return "No channels found";
            if ("no_channels_sub".equals(key)) return "Chat participants have not attached personal channels to their profiles";
            if ("owner_prefix".equals(key)) return "Owner: ";
            if ("owners".equals(key)) return "owners";
            if ("channel".equals(key)) return "Channel";
        }
        return fallback;
    }
}
