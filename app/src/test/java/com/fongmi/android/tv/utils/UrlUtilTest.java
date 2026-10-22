package com.fongmi.android.tv.utils;

import com.fongmi.nodejs.NodeBundleManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UrlUtilTest {

    @Test
    public void isGitUrlDetectsSupportedSchemes() {
        assertTrue(UrlUtil.isGitUrl("gitee://my_token@gitee.com/user/repo/dist/index.js.md5"));
        assertTrue(UrlUtil.isGitUrl("gitee:/user/repo/dist/index.js.md5"));
        assertTrue(UrlUtil.isGitUrl("github://token@github.com/user/repo/dist/index.js.md5"));
        assertTrue(UrlUtil.isGitUrl("github:/user/repo/dist/index.js.md5"));

        assertFalse(UrlUtil.isGitUrl("https://gitee.com/user/repo/raw/dist/index.js.md5"));
        assertFalse(UrlUtil.isGitUrl("assets://web/index.html"));
        assertFalse(UrlUtil.isGitUrl("file:///sdcard/test.js"));
        assertFalse(UrlUtil.isGitUrl(null));
        assertFalse(UrlUtil.isGitUrl(""));
    }

    @Test
    public void convertsStandardGiteeNodeBundleUrl() {
        String input = "gitee://my_token@gitee.com/jadehh/TVSpider/dist/index.js.md5";
        String expected = "https://gitee.com/jadehh/TVSpider/raw/dist/index.js.md5?access_token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGiteeUrlAlreadyContainingRaw() {
        String input = "gitee://my_token@gitee.com/jadehh/TVSpider/raw/dist/index.js.md5";
        String expected = "https://gitee.com/jadehh/TVSpider/raw/dist/index.js.md5?access_token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsPublicGiteeUrlWithoutToken() {
        String input = "gitee://gitee.com/user/repo/dist/index.js.md5";
        String expected = "https://gitee.com/user/repo/raw/dist/index.js.md5";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGiteeUrlWithOmittedHost() {
        String input = "gitee://my_token@user/repo/dist/index.js.md5";
        String expected = "https://gitee.com/user/repo/raw/dist/index.js.md5?access_token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGiteeUrlWithBackslashes() {
        String input = "gitee://my_token@gitee.com\\user\\repo\\dist\\index.js.md5";
        String expected = "https://gitee.com/user/repo/raw/dist/index.js.md5?access_token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGiteeUrlWithExistingQueryParams() {
        String input = "gitee://my_token@gitee.com/user/repo/dist/index.js.md5?branch=test";
        String expected = "https://gitee.com/user/repo/raw/dist/index.js.md5?branch=test&access_token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGiteeUrlWithMd5CompatibilityFormat() {
        String input = "gitee://my_token@gitee.com/user/repo/dist/index.js;md5;1234567890abcdef1234567890abcdef";
        String expected = "https://gitee.com/user/repo/raw/dist/index.js?access_token=my_token;md5;1234567890abcdef1234567890abcdef";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGiteeStandardJsonConfig() {
        String input = "gitee://my_token@gitee.com/user/repo/master/config.json";
        String expected = "https://gitee.com/user/repo/raw/master/config.json?access_token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGithubNodeBundleUrl() {
        String input = "github://my_token@github.com/user/repo/dist/index.js.md5";
        String expected = "https://raw.githubusercontent.com/user/repo/dist/index.js.md5?token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void convertsGithubUrlAlreadyContainingRaw() {
        String input = "github://my_token@github.com/user/repo/raw/dist/index.js.md5";
        String expected = "https://raw.githubusercontent.com/user/repo/dist/index.js.md5?token=my_token";
        assertEquals(expected, UrlUtil.convert(input));
    }

    @Test
    public void nodeBundleManagerRecognizesGiteeConfig() {
        assertTrue(NodeBundleManager.isNodeConfig("gitee://my_token@gitee.com/jadehh/TVSpider/dist/index.js.md5"));
        assertTrue(NodeBundleManager.isNodeConfig("gitee://my_token@gitee.com/jadehh/TVSpider/dist/index.js;md5;1234567890abcdef1234567890abcdef"));
        assertTrue(NodeBundleManager.isNodeConfig("gitee://my_token@gitee.com/jadehh/TVSpider/dist/index.js"));
        assertFalse(NodeBundleManager.isNodeConfig("gitee://my_token@gitee.com/jadehh/TVSpider/master/config.json"));
    }

    @Test
    public void nodeBundleManagerParsesGiteeBundleInfoWithAllSiblingUrls() {
        String input = "gitee://my_token@gitee.com/jadehh/TVSpider/dist/index.js.md5";
        NodeBundleManager.BundleInfo info = NodeBundleManager.parse(input);

        assertEquals("https://gitee.com/jadehh/TVSpider/raw/dist/index.js?access_token=my_token", info.scriptUrl);
        assertEquals("https://gitee.com/jadehh/TVSpider/raw/dist/index.js.md5?access_token=my_token", info.scriptMd5Url);
        assertEquals("https://gitee.com/jadehh/TVSpider/raw/dist/index.config.js?access_token=my_token", info.configUrl);
        assertEquals("https://gitee.com/jadehh/TVSpider/raw/dist/index.config.js.md5?access_token=my_token", info.configMd5Url);
        assertTrue(info.configRequired);
    }

    @Test
    public void nodeBundleManagerParsesGiteeCompatibilityMd5Format() {
        String input = "gitee://my_token@gitee.com/jadehh/TVSpider/dist/index.js;md5;1234567890abcdef1234567890abcdef";
        NodeBundleManager.BundleInfo info = NodeBundleManager.parse(input);

        assertEquals("https://gitee.com/jadehh/TVSpider/raw/dist/index.js?access_token=my_token", info.scriptUrl);
        assertEquals("1234567890abcdef1234567890abcdef", info.scriptMd5);
        assertEquals("", info.scriptMd5Url);
        assertEquals("https://gitee.com/jadehh/TVSpider/raw/dist/index.config.js?access_token=my_token", info.configUrl);
        assertEquals("https://gitee.com/jadehh/TVSpider/raw/dist/index.config.js.md5?access_token=my_token", info.configMd5Url);
        assertFalse(info.configRequired);
    }
}
