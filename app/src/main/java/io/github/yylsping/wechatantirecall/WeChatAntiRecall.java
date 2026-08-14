package io.github.yylsping.wechatantirecall;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/** Modern libxposed API 102 entry point for WeChat 8.0.69. */
public final class WeChatAntiRecall extends XposedModule {
    static final String TARGET_PACKAGE = "com.tencent.mm";
    static final String SUPPORTED_VERSION = "8.0.69";
    static final long SUPPORTED_VERSION_CODE = 3022L;
    static final String CLICK_TEXT = "\u4e00\u6761\u6d88\u606f";
    static final String LOCATOR_PREFIX = "wechat-antirecall://locate?localId=";

    private static final String TAG = "WeChatAntiRecall";
    private static final int CACHE_LIMIT = 512;

    private final AtomicBoolean attachHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean businessHooksInstalled = new AtomicBoolean(false);
    private final BoundedSet<String> insertedMarkers = new BoundedSet<>(CACHE_LIMIT);
    private final BoundedMap<Long, LocatorTarget> markerTargets =
            new BoundedMap<>(CACHE_LIMIT);
    private final BoundedSet<Long> spanAppliedLogged = new BoundedSet<>(CACHE_LIMIT);
    private final BoundedSet<Long> missingTargetLogged = new BoundedSet<>(CACHE_LIMIT);
    private final ScheduledThreadPoolExecutor finalizer = createFinalizer();

    private volatile boolean mainProcess;
    private volatile ReflectionCache reflection;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        mainProcess = TARGET_PACKAGE.equals(param.getProcessName());
        if (!mainProcess) {
            detach();
            return;
        }
        info("module loaded in main process; framework=" + getFrameworkName()
                + ' ' + getFrameworkVersion() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!mainProcess || !TARGET_PACKAGE.equals(param.getPackageName())
                || !param.isFirstPackage()) {
            return;
        }
        if (!attachHookInstalled.compareAndSet(false, true)) return;

        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("wechat-antirecall.application-attach")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Context context = (Context) chain.getArg(0);
                        installBusinessHooks(context, param.getClassLoader());
                        return result;
                    });
            info("deterministic attach boundary armed; no delayed protection window");
        } catch (Throwable error) {
            failure("failed to hook Application.attach", error);
        }
    }

    private void installBusinessHooks(Context context, ClassLoader loader) {
        if (!businessHooksInstalled.compareAndSet(false, true)) return;
        try {
            Context appContext = context.getApplicationContext();
            PackageInfo info = appContext.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            this.info("loading for WeChat " + info.versionName + " (" + versionCode + ")");
            if (!SUPPORTED_VERSION.equals(info.versionName)
                    || SUPPORTED_VERSION_CODE != versionCode) {
                this.info("unsupported version; business hooks are not installed");
                return;
            }

            ReflectionCache cache = ReflectionCache.resolve(loader);
            reflection = cache;
            hookNativeLocatorSpan(cache);
            hookSystemMessageRenderer(cache);
            hookRevokeHandler(cache);
            this.info("hooks installed at Application.attach: recall path + native locator");
        } catch (Throwable error) {
            failure("business hook installation failed", error);
        }
    }

    private void hookRevokeHandler(ReflectionCache cache) {
        hook(cache.revokeMethod)
                .setPriority(10_000)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("wechat-antirecall.revoke")
                .intercept(chain -> {
                    RecallContext recall;
                    try {
                        String talker = (String) chain.getArg(0);
                        long serverId = ((Number) chain.getArg(1)).longValue();
                        Object storage = cache.getMessageStorage();
                        Object original = invoke(cache.storageFindMessage, storage,
                                talker, serverId);
                        long localId = number(invoke(cache.messageGetId, original)).longValue();
                        if (localId == 0) {
                            info("revoke target not found serverId=" + serverId);
                            return chain.proceed();
                        }

                        int type = number(invoke(cache.messageGetType, original)).intValue();
                        boolean nativeRevokeProcessed = Boolean.TRUE.equals(
                                invoke(cache.messageIsNativeRevokeProcessed, original));
                        if (nativeRevokeProcessed || isNativeRecallType(type)) {
                            info("native/self revoke already processed localId=" + localId
                                    + " type=" + type + "; native handling preserved");
                            return chain.proceed();
                        }

                        int isSend = number(invoke(cache.messageIsSend, original)).intValue();
                        if (isSend != 0) {
                            info("self revoke identified localId=" + localId
                                    + "; native handling preserved");
                            return chain.proceed();
                        }

                        long createTime = number(invoke(
                                cache.messageGetCreateTime, original)).longValue();
                        recall = new RecallContext(talker, serverId, localId, type, createTime,
                                buildRecallNotice((String) chain.getArg(3)));
                    } catch (Throwable error) {
                        failure("revoke inspection failed; native handling preserved", error);
                        return chain.proceed();
                    }

                    // W6 already received and parsed the protocol event. Skip only g's local
                    // destructive body, then insert the marker outside the sync callback locks.
                    info("revoke identified localId=" + recall.localId
                            + " serverId=" + recall.serverId
                            + " originalType=" + recall.originalType
                            + "; local overwrite skipped");
                    try {
                        finalizer.schedule(() -> finalizeRecall(recall),
                                750, TimeUnit.MILLISECONDS);
                    } catch (Throwable error) {
                        failure("recall marker scheduling failed", error);
                    }
                    return null;
                });
    }

    private void hookNativeLocatorSpan(ReflectionCache cache) {
        hook(cache.spanOnClick)
                .setPriority(10_000)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("wechat-antirecall.native-span-click")
                .intercept(chain -> {
                    Object tag;
                    try {
                        tag = invoke(cache.spanGetTag, chain.getThisObject());
                    } catch (Throwable error) {
                        failure("native span tag read failed", error);
                        return chain.proceed();
                    }
                    if (!(tag instanceof LocatorTarget)) return chain.proceed();

                    LocatorTarget target = (LocatorTarget) tag;
                    new Handler(Looper.getMainLooper()).post(
                            () -> dispatchNativeLocation(target));
                    return null;
                });
    }

    private static boolean isNativeRecallType(int type) {
        return type == 268445456 || type == 268445458 || type == 285222674;
    }

    private void hookSystemMessageRenderer(ReflectionCache cache) {
        hook(cache.rendererMethod)
                .setPriority(10_000)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("wechat-antirecall.system-renderer")
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object item = chain.getArg(3);
                        Object holder = cache.itemHolderField.get(item);
                        Object message = cache.holderMessageField.get(holder);
                        int type = number(invoke(cache.messageGetType, message)).intValue();
                        if (type != 10000) return result;

                        String text = (String) invoke(cache.messageGetContent, message);
                        int start = text == null ? -1 : text.lastIndexOf(CLICK_TEXT);
                        if (start < 0 || start + CLICK_TEXT.length() != text.length()) {
                            return result;
                        }

                        String talker = (String) invoke(cache.messageGetTalker, message);
                        long markerLocalId = number(invoke(
                                cache.messageGetId, message)).longValue();
                        long originalLocalId = resolveOriginalLocalId(
                                cache, message, markerLocalId);
                        if (originalLocalId <= 0) {
                            if (missingTargetLogged.add(markerLocalId)) {
                                info("locator target unavailable markerLocalId=" + markerLocalId);
                            }
                            return result;
                        }

                        Object nativeSpan = cache.spanConstructor.newInstance();
                        invoke(cache.spanSetColorConfig, nativeSpan, 1);
                        invoke(cache.spanSetTextBold, nativeSpan, true);
                        invoke(cache.spanSetTag, nativeSpan,
                                new LocatorTarget(talker, originalLocalId));

                        SpannableString styled = new SpannableString(text);
                        styled.setSpan(nativeSpan, start, text.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        Object neatTextView = cache.getNeatTextView(chain.getArg(0));
                        invoke(cache.spanSetContext, nativeSpan,
                                ((View) neatTextView).getContext());
                        cache.setNeatText(neatTextView, styled);
                        ((View) neatTextView).setClickable(true);
                        ((View) neatTextView).invalidate();
                        if (spanAppliedLogged.add(markerLocalId)) {
                            info("native locator span applied markerLocalId=" + markerLocalId
                                    + " targetLocalId=" + originalLocalId
                                    + " range=" + start + '-' + text.length());
                        }
                    } catch (Throwable error) {
                        failure("system marker rendering failed", error);
                    }
                    return result;
                });
    }

    private void dispatchNativeLocation(LocatorTarget target) {
        ReflectionCache cache = reflection;
        if (cache == null) return;
        try {
            Object event = cache.eventConstructor.newInstance();
            Object payload = cache.eventPayloadField.get(event);
            cache.payloadTalkerField.set(payload, target.talker);
            cache.payloadLocalIdField.setLong(payload, target.localId);
            invoke(cache.eventPublish, event);
            info("native locator dispatched targetLocalId=" + target.localId);
        } catch (Throwable error) {
            failure("native locator dispatch failed", error);
        }
    }

    private long resolveOriginalLocalId(
            ReflectionCache cache, Object message, long markerLocalId) {
        try {
            long id = parseOriginalLocalId((String) invoke(cache.messageGetMsgSource, message));
            if (id > 0) return id;
        } catch (Throwable ignored) {
        }
        try {
            long id = parseOriginalLocalId((String) invoke(cache.messageGetReserved, message));
            if (id > 0) return id;
        } catch (Throwable ignored) {
        }
        LocatorTarget cached = markerTargets.get(markerLocalId);
        if (cached != null && cached.localId > 0) return cached.localId;

        // Compatibility for markers created by 1.3.0. New markers always persist an
        // explicit locator in two database-backed fields.
        return markerLocalId > 1 ? markerLocalId - 1 : -1L;
    }

    private static long parseOriginalLocalId(String locator) {
        if (locator == null || !locator.startsWith(LOCATOR_PREFIX)) return -1L;
        try {
            return Long.parseLong(locator.substring(LOCATOR_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String buildRecallNotice(String nativeReplacement) {
        if (nativeReplacement == null || nativeReplacement.isEmpty()) {
            return "\u5bf9\u65b9\u5c1d\u8bd5\u64a4\u56de\u4e00\u6761\u6d88\u606f";
        }
        String[] revokePhrases = {
                "\u64a4\u56de\u4e86\u4e00\u6761\u6d88\u606f",
                "\u64a4\u56de\u4e00\u6761\u6d88\u606f",
                "\u64a4\u56de"
        };
        for (String phrase : revokePhrases) {
            int index = nativeReplacement.indexOf(phrase);
            if (index >= 0) {
                return nativeReplacement.substring(0, index)
                        + "\u5c1d\u8bd5\u64a4\u56de\u4e00\u6761\u6d88\u606f";
            }
        }
        return "\u5bf9\u65b9\u5c1d\u8bd5\u64a4\u56de\u4e00\u6761\u6d88\u606f";
    }

    private void finalizeRecall(RecallContext recall) {
        try {
            insertMarkerOnce(recall);
        } catch (Throwable error) {
            insertedMarkers.remove(markerKey(recall));
            failure("recall marker insertion failed", error);
        }
    }

    private void insertMarkerOnce(RecallContext recall) throws Throwable {
        String key = markerKey(recall);
        if (!insertedMarkers.add(key)) return;
        ReflectionCache cache = reflection;
        if (cache == null) {
            insertedMarkers.remove(key);
            return;
        }

        Object marker = cache.messageConstructor.newInstance();
        invoke(cache.messageSetTalker, marker, recall.talker);
        invoke(cache.messageSetContent, marker, recall.noticeText);
        invoke(cache.messageSetType, marker, 10000);
        invoke(cache.messageSetCreateTime, marker,
                Math.max(System.currentTimeMillis(), recall.createTime + 1));
        invoke(cache.messageSetIsSend, marker, 0);
        invoke(cache.messageSetStatus, marker, 6);
        String locator = LOCATOR_PREFIX + recall.localId;
        invoke(cache.messageSetMsgSource, marker, locator);
        invoke(cache.messageSetReserved, marker, locator);

        long insertedId = number(invoke(cache.insertMessage, null, marker)).longValue();
        if (insertedId > 0) {
            markerTargets.put(insertedId, new LocatorTarget(recall.talker, recall.localId));
            info("recall marker inserted localId=" + insertedId
                    + " for serverId=" + recall.serverId);
        } else {
            insertedMarkers.remove(key);
            info("recall marker insert failed for serverId=" + recall.serverId);
        }
    }

    private static String markerKey(RecallContext recall) {
        return Integer.toHexString(recall.talker.hashCode()) + ':' + recall.serverId;
    }

    private static ScheduledThreadPoolExecutor createFinalizer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "wechat-anti-recall-finalizer");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private void info(String message) {
        log(Log.INFO, TAG, message);
    }

    private void failure(String message, Throwable error) {
        log(Log.ERROR, TAG, message, error);
    }

    private static Number number(Object value) {
        return (Number) value;
    }

    private static Object invoke(Method method, Object receiver, Object... args)
            throws Throwable {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }

    private static final class ReflectionCache {
        final Constructor<?> messageConstructor;
        final Constructor<?> spanConstructor;
        final Constructor<?> eventConstructor;

        final Method coreGetInstance;
        final Method coreGetStorage;
        final Method storageFindMessage;
        final Method messageGetId;
        final Method messageGetType;
        final Method messageGetCreateTime;
        final Method messageIsNativeRevokeProcessed;
        final Method messageIsSend;
        final Method messageGetContent;
        final Method messageGetTalker;
        final Method messageGetMsgSource;
        final Method messageGetReserved;
        final Method messageSetTalker;
        final Method messageSetContent;
        final Method messageSetType;
        final Method messageSetCreateTime;
        final Method messageSetIsSend;
        final Method messageSetStatus;
        final Method messageSetMsgSource;
        final Method messageSetReserved;
        final Method insertMessage;
        final Method revokeMethod;
        final Method rendererMethod;
        final Method spanOnClick;
        final Method spanGetTag;
        final Method spanSetColorConfig;
        final Method spanSetTextBold;
        final Method spanSetTag;
        final Method spanSetContext;
        volatile Method neatTextSetText;
        final Method eventPublish;

        final Field itemHolderField;
        final Field holderMessageField;
        volatile Field neatTextViewField;
        final Field eventPayloadField;
        final Field payloadTalkerField;
        final Field payloadLocalIdField;

        private ReflectionCache(ClassLoader loader) throws Exception {
            Class<?> messageClass = load(loader, "com.tencent.mm.storage.y8");
            Class<?> messageStorageClass = load(loader, "com.tencent.mm.storage.a9");
            Class<?> coreClass = load(loader, "iy0.c9");
            Class<?> messageLogicClass = load(loader, "iy0.v9");
            Class<?> consumerClass = load(loader, "iy0.u");
            Class<?> envelopeClass = load(loader, "com.tencent.mm.modelbase.p0");
            Class<?> spanClass = load(loader, "com.tencent.mm.pluginsdk.ui.span.z0");
            Class<?> rendererClass = load(loader, "com.tencent.mm.ui.chatting.viewitems.xo");
            Class<?> eventClass = load(loader,
                    "com.tencent.mm.autogen.events.ScrollChattingUIConversationListEvent");

            messageConstructor = constructor(messageClass);
            spanConstructor = constructor(spanClass);
            eventConstructor = constructor(eventClass);

            coreGetInstance = method(coreClass, "b");
            coreGetStorage = method(coreGetInstance.getReturnType(), "v");
            storageFindMessage = method(messageStorageClass, "f3", String.class, long.class);
            messageGetId = method(messageClass, "getMsgId");
            messageGetType = method(messageClass, "getType");
            messageGetCreateTime = method(messageClass, "getCreateTime");
            messageIsNativeRevokeProcessed = method(messageClass, "U2");
            messageIsSend = method(messageClass, "E0");
            messageGetContent = method(messageClass, "j");
            messageGetTalker = method(messageClass, "Q0");
            messageGetMsgSource = method(messageClass, "f2");
            messageGetReserved = method(messageClass, "L0");
            messageSetTalker = method(messageClass, "I1", String.class);
            messageSetContent = method(messageClass, "h1", String.class);
            messageSetType = method(messageClass, "setType", int.class);
            messageSetCreateTime = method(messageClass, "i1", long.class);
            messageSetIsSend = method(messageClass, "n1", int.class);
            messageSetStatus = method(messageClass, "D1", int.class);
            messageSetMsgSource = method(messageClass, "w3", String.class);
            messageSetReserved = method(messageClass, "z1", String.class);
            insertMessage = method(messageLogicClass, "x", messageClass);
            revokeMethod = findRevokeMethod(consumerClass, envelopeClass);
            rendererMethod = findRendererMethod(rendererClass);

            spanOnClick = method(spanClass, "onClick", View.class);
            spanGetTag = method(spanClass, "getTag");
            spanSetColorConfig = method(spanClass, "setColorConfig", int.class);
            spanSetTextBold = method(spanClass, "setTextBold", boolean.class);
            spanSetTag = compatibleMethod(spanClass, "setTag", Object.class);
            spanSetContext = compatibleMethod(spanClass, "setContext", Context.class);

            itemHolderField = field(rendererMethod.getParameterTypes()[3], "d");
            holderMessageField = field(itemHolderField.getType(), "b");
            // xo declares its first parameter as the g0 base holder, while the actual
            // field b lives on the concrete holder. Resolve that one field lazily from
            // the first rendered system row, then reuse it for subsequent bindings.
            neatTextSetText = null;

            eventPayloadField = field(eventClass, "g");
            payloadTalkerField = field(eventPayloadField.getType(), "a");
            payloadLocalIdField = field(eventPayloadField.getType(), "b");
            eventPublish = method(eventClass, "e");
        }

        static ReflectionCache resolve(ClassLoader loader) throws Exception {
            return new ReflectionCache(loader);
        }

        Object getMessageStorage() throws Throwable {
            Object core = invoke(coreGetInstance, null);
            return invoke(coreGetStorage, core);
        }

        Object getNeatTextView(Object rowHolder) throws IllegalAccessException,
                NoSuchFieldException {
            Field value = neatTextViewField;
            if (value == null || !value.getDeclaringClass().isInstance(rowHolder)) {
                synchronized (this) {
                    value = neatTextViewField;
                    if (value == null || !value.getDeclaringClass().isInstance(rowHolder)) {
                        value = field(rowHolder.getClass(), "b");
                        neatTextViewField = value;
                    }
                }
            }
            return value.get(rowHolder);
        }

        void setNeatText(Object neatTextView, CharSequence text) throws Throwable {
            Method value = neatTextSetText;
            if (value == null || !value.getDeclaringClass().isInstance(neatTextView)) {
                synchronized (this) {
                    value = neatTextSetText;
                    if (value == null || !value.getDeclaringClass().isInstance(neatTextView)) {
                        value = findNeatTextSetter(neatTextView.getClass());
                        neatTextSetText = value;
                    }
                }
            }
            invoke(value, neatTextView, text, TextView.BufferType.SPANNABLE, null);
        }

        private static Class<?> load(ClassLoader loader, String name)
                throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }

        private static Constructor<?> constructor(Class<?> type) throws Exception {
            Constructor<?> value = type.getDeclaredConstructor();
            value.setAccessible(true);
            return value;
        }

        private static Method method(Class<?> type, String name, Class<?>... params)
                throws NoSuchMethodException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Method value = current.getDeclaredMethod(name, params);
                    value.setAccessible(true);
                    return value;
                } catch (NoSuchMethodException ignored) {
                }
            }
            throw new NoSuchMethodException(type.getName() + '#' + name);
        }

        private static Method compatibleMethod(
                Class<?> type, String name, Class<?>... argumentTypes)
                throws NoSuchMethodException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method candidate : current.getDeclaredMethods()) {
                    Class<?>[] params = candidate.getParameterTypes();
                    if (!candidate.getName().equals(name) || params.length != argumentTypes.length) {
                        continue;
                    }
                    boolean compatible = true;
                    for (int i = 0; i < params.length; i++) {
                        if (!box(params[i]).isAssignableFrom(box(argumentTypes[i]))) {
                            compatible = false;
                            break;
                        }
                    }
                    if (compatible) {
                        candidate.setAccessible(true);
                        return candidate;
                    }
                }
            }
            throw new NoSuchMethodException(type.getName() + '#' + name);
        }

        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field value = current.getDeclaredField(name);
                    value.setAccessible(true);
                    return value;
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw new NoSuchFieldException(type.getName() + '#' + name);
        }

        private static Method findRevokeMethod(Class<?> type, Class<?> envelopeClass)
                throws NoSuchMethodException {
            try {
                return method(type, "g", String.class, long.class, envelopeClass,
                        String.class, String.class, String.class);
            } catch (NoSuchMethodException ignored) {
                for (Method candidate : type.getDeclaredMethods()) {
                    Class<?>[] p = candidate.getParameterTypes();
                    if (Modifier.isPublic(candidate.getModifiers())
                            && candidate.getReturnType() == void.class
                            && p.length == 6
                            && p[0] == String.class
                            && p[1] == long.class
                            && p[2] == envelopeClass
                            && p[3] == String.class
                            && p[4] == String.class
                            && p[5] == String.class) {
                        candidate.setAccessible(true);
                        return candidate;
                    }
                }
                throw new NoSuchMethodException(type.getName() + " revoke handler");
            }
        }

        private static Method findRendererMethod(Class<?> type) throws NoSuchMethodException {
            for (Method candidate : type.getDeclaredMethods()) {
                Class<?>[] p = candidate.getParameterTypes();
                if (candidate.getName().equals("a")
                        && candidate.getReturnType() == void.class
                        && p.length == 5
                        && p[4] == String.class
                        && p[3].getName().equals("n95.d")) {
                    candidate.setAccessible(true);
                    return candidate;
                }
            }
            throw new NoSuchMethodException(type.getName() + " renderer");
        }

        private static Method findNeatTextSetter(Class<?> type) throws NoSuchMethodException {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method candidate : current.getDeclaredMethods()) {
                    Class<?>[] p = candidate.getParameterTypes();
                    if (candidate.getName().equals("c")
                            && p.length == 3
                            && CharSequence.class.isAssignableFrom(p[0])
                            && p[1] == TextView.BufferType.class
                            && !p[2].isPrimitive()) {
                        candidate.setAccessible(true);
                        return candidate;
                    }
                }
            }
            throw new NoSuchMethodException(type.getName() + " spannable setter");
        }

        private static Class<?> box(Class<?> type) {
            if (!type.isPrimitive()) return type;
            if (type == boolean.class) return Boolean.class;
            if (type == byte.class) return Byte.class;
            if (type == char.class) return Character.class;
            if (type == short.class) return Short.class;
            if (type == int.class) return Integer.class;
            if (type == long.class) return Long.class;
            if (type == float.class) return Float.class;
            if (type == double.class) return Double.class;
            return Void.class;
        }
    }

    private static final class RecallContext {
        final String talker;
        final long serverId;
        final long localId;
        final int originalType;
        final long createTime;
        final String noticeText;

        RecallContext(String talker, long serverId, long localId, int originalType,
                      long createTime, String noticeText) {
            this.talker = talker;
            this.serverId = serverId;
            this.localId = localId;
            this.originalType = originalType;
            this.createTime = createTime;
            this.noticeText = noticeText;
        }
    }

    private static final class LocatorTarget {
        final String talker;
        final long localId;

        LocatorTarget(String talker, long localId) {
            this.talker = talker;
            this.localId = localId;
        }
    }

    private static final class BoundedSet<K> {
        private final int limit;
        private final LinkedHashMap<K, Boolean> values = new LinkedHashMap<>();

        BoundedSet(int limit) {
            this.limit = limit;
        }

        synchronized boolean add(K key) {
            if (values.containsKey(key)) return false;
            values.put(key, Boolean.TRUE);
            trim(values, limit);
            return true;
        }

        synchronized void remove(K key) {
            values.remove(key);
        }
    }

    private static final class BoundedMap<K, V> {
        private final int limit;
        private final LinkedHashMap<K, V> values = new LinkedHashMap<>();

        BoundedMap(int limit) {
            this.limit = limit;
        }

        synchronized void put(K key, V value) {
            values.remove(key);
            values.put(key, value);
            trim(values, limit);
        }

        synchronized V get(K key) {
            return values.get(key);
        }
    }

    private static <K, V> void trim(LinkedHashMap<K, V> values, int limit) {
        while (values.size() > limit) {
            K eldest = values.keySet().iterator().next();
            values.remove(eldest);
        }
    }
}
