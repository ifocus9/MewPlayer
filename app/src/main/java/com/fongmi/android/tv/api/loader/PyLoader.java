package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 爬虫加载器（Chaquopy，见 :chaquo 模块）。
 * Python 运行时在第一次用到 .py 站点时才启动（懒加载），不影响只用 jar / JS / T4 源时的启动速度。
 */
public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private volatile Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    private Loader loader() {
        if (loader == null) {
            synchronized (this) {
                if (loader == null) loader = new Loader();
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
                Spider spider = loader().spider(api);
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("PyLoader", "Python spider init failed: key=%s api=%s", key, api);
                SpiderDebug.log("PyLoader", e);
                e.printStackTrace();
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
