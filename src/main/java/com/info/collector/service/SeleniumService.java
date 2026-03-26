package com.info.collector.service;

import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;
import io.github.bonigarcia.wdm.WebDriverManager;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Selenium 浏览器自动化服务
 * 用于抓取有反爬虫保护的网站（如中央纪委网站）
 */
@Slf4j
@Service
public class SeleniumService {

    private WebDriver driver;
    private final Object driverLock = new Object();
    private boolean initialized = false;

    /**
     * 初始化 WebDriver（懒加载）
     */
    private void initDriver() {
        if (initialized && driver != null) {
            return;
        }

        synchronized (driverLock) {
            if (initialized && driver != null) {
                return;
            }

            try {
                log.info("正在初始化 Selenium WebDriver...");

                // 自动下载和管理 ChromeDriver
                WebDriverManager.chromedriver().setup();

                ChromeOptions options = new ChromeOptions();

                // 无头模式（服务器环境必须）
                options.addArguments("--headless=new");

                // 反检测配置
                options.addArguments("--disable-blink-features=AutomationControlled");
                options.setExperimentalOption("excludeSwitches", new String[]{"enable-automation"});

                // 基础配置
                options.addArguments("--no-sandbox");
                options.addArguments("--disable-dev-shm-usage");
                options.addArguments("--disable-gpu");
                options.addArguments("--window-size=1920,1080");
                options.addArguments("--lang=zh-CN");

                // 解决 WebSocket 连接问题
                options.addArguments("--disable-extensions");
                options.addArguments("--disable-plugins");
                options.addArguments("--disable-software-rasterizer");
                options.addArguments("--disable-web-security");
                options.addArguments("--remote-allow-origins=*");

                // 设置 User-Agent
                options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

                // 禁用图片加载（加速）
                options.addArguments("--blink-settings=imagesEnabled=false");

                driver = new ChromeDriver(options);

                // 设置隐式等待
                driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
                driver.manage().timeouts().pageLoadTimeout(60, TimeUnit.SECONDS);

                initialized = true;
                log.info("Selenium WebDriver 初始化完成");

            } catch (Exception e) {
                log.error("Selenium WebDriver 初始化失败: {}", e.getMessage(), e);
                throw new RuntimeException("无法初始化 Selenium WebDriver", e);
            }
        }
    }

    /**
     * 使用 Selenium 获取页面 HTML（支持 JavaScript 渲染）
     *
     * @param url 页面 URL
     * @return 页面 HTML
     */
    public String getPageHtml(String url) {
        return getPageHtml(url, 10);
    }

    /**
     * 使用 Selenium 获取页面 HTML
     *
     * @param url 页面 URL
     * @param waitSeconds 等待页面加载的秒数
     * @return 页面 HTML
     */
    public String getPageHtml(String url, int waitSeconds) {
        initDriver();

        synchronized (driverLock) {
            try {
                log.debug("Selenium 正在访问: {}", url);
                driver.get(url);

                // 等待页面加载
                Thread.sleep(2000);

                // 检查是否有验证码
                if (hasCaptcha()) {
                    log.warn("检测到验证码，尝试等待自动通过...");
                    waitForCaptchaResolution(waitSeconds);
                }

                // 获取页面 HTML
                String html = driver.getPageSource();
                log.debug("Selenium 获取页面成功，HTML 长度: {}", html.length());

                return html;

            } catch (TimeoutException e) {
                log.warn("Selenium 页面加载超时: {}", url);
                return driver.getPageSource();
            } catch (Exception e) {
                log.error("Selenium 获取页面失败: {} - {}", url, e.getMessage());
                return null;
            }
        }
    }

    /**
     * 使用 Selenium 获取页面并转换为 Jsoup Document
     */
    public org.jsoup.nodes.Document getPageDocument(String url) {
        return getPageDocument(url, 10);
    }

    /**
     * 使用 Selenium 获取页面并转换为 Jsoup Document
     */
    public org.jsoup.nodes.Document getPageDocument(String url, int waitSeconds) {
        String html = getPageHtml(url, waitSeconds);
        if (html == null) {
            return null;
        }
        return org.jsoup.Jsoup.parse(html, url);
    }

    /**
     * 检查页面是否有验证码
     */
    private boolean hasCaptcha() {
        try {
            // 检查常见的验证码元素
            WebElement captcha = driver.findElement(
                By.cssSelector(".captchaPage, .slider, #captcha, [class*='captcha'], [class*='verify']")
            );
            return captcha != null && captcha.isDisplayed();
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    /**
     * 等待验证码自动通过（某些情况下验证码会自动消失）
     */
    private void waitForCaptchaResolution(int maxWaitSeconds) {
        try {
            int waited = 0;
            while (waited < maxWaitSeconds && hasCaptcha()) {
                Thread.sleep(1000);
                waited++;
                log.debug("等待验证码通过... {}/{}", waited, maxWaitSeconds);
            }

            if (!hasCaptcha()) {
                log.info("验证码已通过");
            } else {
                log.warn("验证码未能自动通过，可能需要人工干预");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 执行 JavaScript
     */
    public Object executeScript(String script, Object... args) {
        initDriver();
        synchronized (driverLock) {
            return ((JavascriptExecutor) driver).executeScript(script, args);
        }
    }

    /**
     * 滚动到页面底部（触发懒加载）
     */
    public void scrollToBottom() {
        initDriver();
        synchronized (driverLock) {
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, document.body.scrollHeight)");
        }
    }

    /**
     * 获取 Cookie 字符串
     */
    public String getCookies() {
        initDriver();
        synchronized (driverLock) {
            StringBuilder sb = new StringBuilder();
            for (Cookie cookie : driver.manage().getCookies()) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(cookie.getName()).append("=").append(cookie.getValue());
            }
            return sb.toString();
        }
    }

    /**
     * 关闭 WebDriver
     */
    @PreDestroy
    public void destroy() {
        if (driver != null) {
            try {
                log.info("正在关闭 Selenium WebDriver...");
                driver.quit();
                driver = null;
                initialized = false;
                log.info("Selenium WebDriver 已关闭");
            } catch (Exception e) {
                log.warn("关闭 Selenium WebDriver 时出错: {}", e.getMessage());
            }
        }
    }

    /**
     * 检查 Selenium 是否可用
     */
    public boolean isAvailable() {
        try {
            initDriver();
            return driver != null;
        } catch (Exception e) {
            return false;
        }
    }
}
