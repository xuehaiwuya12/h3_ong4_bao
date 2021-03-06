package pu.hongbao.services;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcelable;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pu.hongbao.utils.HongbaoSignature;

/**
 * Created by koen on 2016/1/29.
 */
public class PKHongbaoService extends AccessibilityService {

    private static final String WECHAT_DETAILS_EN = "Details";
    private static final String WECHAT_DETAILS_CH = "红包详情";
    private static final String WECHAT_BETTER_LUCK_EN = "Better luck next time!";
    private static final String WECHAT_BETTER_LUCK_CH = "手慢了";
    private static final String WECHAT_EXPIRES_CH = "红包已失效";
    private static final String WECHAT_VIEW_SELF_CH = "查看红包";
    private static final String WECHAT_VIEW_OTHERS_CH = "领取红包";
    private static final String WECHAT_NOTIFICATION_TIP = "[微信红包]";
    private static final String WECHAT_NOTIFICATION_TIP2 = "个联系人发来";
    private static final String WECHAT_LUCKMONEY_RECEIVE_ACTIVITY = "LuckyMoneyReceiveUI";
    private static final String WECHAT_LUCKMONEY_DETAIL_ACTIVITY = "LuckyMoneyDetailUI";
    private static final String WECHAT_LUCKMONEY_GENERAL_ACTIVITY = "LauncherUI";
    private String currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;

    private AccessibilityNodeInfo rootNodeInfo, mReceiveNode, mUnpackNode; //根node, 收到的红包, 未打开的红包
    private boolean mLuckyMoneyPicked, mLuckyMoneyReceived, mNeedUnpack;
    private HongbaoSignature signature = new HongbaoSignature();
    private boolean mMutex = false;  //是否在占用中

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        setCurrentActivityName(event);

        if (!mMutex) {
           // if (watchNotification(event)) return;
            if (watchList(event)) return;
        }
        watchChat(event);
    }

    private void setCurrentActivityName(AccessibilityEvent event) {

        ComponentName componentName = new ComponentName(
                event.getPackageName().toString(),
                event.getClassName().toString()
        );

        try {
            getPackageManager().getActivityInfo(componentName, 0);
            currentActivityName = componentName.flattenToShortString();
        } catch (PackageManager.NameNotFoundException e) {
            currentActivityName = WECHAT_LUCKMONEY_GENERAL_ACTIVITY;
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
    }

    @Override
    public void onInterrupt() {

    }


    // notification调用成功返回true， 失败返回false
    private boolean watchNotification(AccessibilityEvent event) {
        boolean isRun = false;
        if (event.getEventType() ==
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            String tip = event.getText().toString();
            if (tip.contains(WECHAT_NOTIFICATION_TIP) || tip.contains(WECHAT_NOTIFICATION_TIP2)) {
                Parcelable parcelable = event.getParcelableData();
                if (parcelable != null && parcelable instanceof Notification) {
                    Notification notification = (Notification) parcelable;
                    try {
                        /* 清除signature,避免进入会话后误判 */
                        signature.cleanSignature();
                        notification.contentIntent.send();
                        isRun = true;
                    } catch (PendingIntent.CanceledException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return isRun;
    }

    //在聊天列表
    private boolean watchList(AccessibilityEvent event) {
        boolean isRun = false;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && event.getSource() != null) {
            List<AccessibilityNodeInfo> nodes = event.getSource()
                    .findAccessibilityNodeInfosByText(WECHAT_NOTIFICATION_TIP);
            if (!nodes.isEmpty()) {
                AccessibilityNodeInfo nodeToClick = nodes.get(0); //只打开最上面的一个
                CharSequence contentDescription = nodeToClick.getContentDescription();
                l("本次的contentDescription==="+ contentDescription);
                if (contentDescription != null && !signature.getContentDescription().equals(contentDescription)) {
                    l("上次的contentDescription==="+ signature.getContentDescription());
                    nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    signature.setContentDescription(contentDescription.toString());
                    isRun = true;
                    l("watchList被调用");
                }
            }
        }
        return isRun;
    }

    //在聊天页面
    private void watchChat(AccessibilityEvent event) {
        l("watchChat被调用");
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            this.rootNodeInfo = getRootInActiveWindow();
            if (rootNodeInfo == null) return;
            mReceiveNode = null;
            mUnpackNode = null;
            checkNodeInfo(event.getEventType());

            /* 如果已经接收到红包并且还没有戳开 */
            if (mLuckyMoneyReceived && !mLuckyMoneyPicked && (mReceiveNode != null)) {
                mMutex = true;

                AccessibilityNodeInfo cellNode = mReceiveNode;
                l("红包未被拆开");
                cellNode.getParent().performAction(AccessibilityNodeInfo.ACTION_CLICK);
                mLuckyMoneyReceived = false;
                mLuckyMoneyPicked = true;

            }
        /* 如果戳开但还未领取 */
            if (mNeedUnpack && (mUnpackNode != null)) {
                AccessibilityNodeInfo cellNode = mUnpackNode;
                l("红包未领取");
                cellNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                mNeedUnpack = false;
            }
        }
    }

    private void checkNodeInfo(int eventType) {

        AccessibilityNodeInfo node1 = getTheLastNode(WECHAT_VIEW_OTHERS_CH, WECHAT_VIEW_SELF_CH);
        if (node1 != null && currentActivityName.contains(WECHAT_LUCKMONEY_GENERAL_ACTIVITY)) {
            String excludeWords = "";
            if (this.signature.generateSignature(node1, excludeWords)) {
                mLuckyMoneyReceived = true;
                mReceiveNode = node1;
            }
            return;
        }

        /* 戳开红包，红包还没抢完，遍历节点匹配“拆红包” */
        AccessibilityNodeInfo node2 = (this.rootNodeInfo.getChildCount() > 3) ? this.rootNodeInfo.getChild(3) : null;
        if (node2 != null && "android.widget.Button".equals(node2.getClassName()) && currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY)) {
            mUnpackNode = node2;
            mNeedUnpack = true;
            return;
        }

        /* 戳开红包，红包已被抢完，遍历节点匹配“红包详情”和“手慢了” */
        boolean hasNodes = this.hasOneOfThoseNodes(
                WECHAT_BETTER_LUCK_CH, WECHAT_DETAILS_CH,
                WECHAT_BETTER_LUCK_EN, WECHAT_DETAILS_EN, WECHAT_EXPIRES_CH);
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && hasNodes
                && (currentActivityName.contains(WECHAT_LUCKMONEY_DETAIL_ACTIVITY)
                || currentActivityName.contains(WECHAT_LUCKMONEY_RECEIVE_ACTIVITY))) {
            mMutex = false;
            mLuckyMoneyPicked = false;
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_BACK);
            l("红包没了退出");
        }
    }

    private boolean hasOneOfThoseNodes(String... texts) {
        for (String text : texts) {
            if (text == null) continue;

            List<AccessibilityNodeInfo> nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText(text);

            if (!nodes.isEmpty()) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo getTheLastNode(String... texts) {
        int bottom = 0;
        AccessibilityNodeInfo lastNode = null;

        for (String text : texts) {
            if (text == null) continue;

            List<AccessibilityNodeInfo> nodes = this.rootNodeInfo.findAccessibilityNodeInfosByText(text);

            if (!nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.get(nodes.size() - 1); //点开最后一个
                lastNode = node;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.bottom > bottom) {
                    bottom = bounds.bottom;
                    lastNode = node;
                }
            }
        }
        return lastNode;
    }



    private void l(String s) {
        Log.e("koen", s);
    }
}
