package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 爬虫加载器（Chaquopy，见 :chaquo 模块）。
 * Python 运行时在第一次用到 .py 站点时才启动（懒加载），不影响只用 jar / JS / T4 源时的启动速度。
 * 支持在不打包 :chaquo 模块时安全降级（返回 SpiderNull，不闪退）。
 */
public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private volatile Object loader;
    private volatile Method spiderMethod;
    private volatile boolean unavailable = false;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    private Object loader() {
        if (unavailable) return null;
        if (loader == null) {
            synchronized (this) {
                if (loader == null && !unavailable) {
                    try {
                        Class<?> clazz = Class.forName("com.fongmi.chaquo.Loader");
                        loader = clazz.getDeclaredConstructor().newInstance();
                        spiderMethod = clazz.getMethod("spider", String.class);
                    } catch (ClassNotFoundException e) {
                        unavailable = true;
                        SpiderDebug.log("PyLoader", "Python runtime (:chaquo) is not bundled in this build.");
                        return null;
                    } catch (Throwable t) {
                        unavailable = true;
                        SpiderDebug.log("PyLoader", "Failed to initialize Python loader: " + t.getMessage());
                        return null;
                    }
                }
            }
        }
        return loader;
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                Object l = loader();
                if (l == null || spiderMethod == null) return new SpiderNull();
                Spider spider = (Spider) spiderMethod.invoke(l, api);
                if (spider == null) return new SpiderNull();
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("PyLoader", "Python spider init failed: key=%s api=%s", key, api);
                SpiderDebug.log("PyLoader", e);
                return new SpiderNull();
            }
        });
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
