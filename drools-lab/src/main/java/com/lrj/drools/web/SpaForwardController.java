package com.lrj.drools.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前后端分离 SPA 的 history 路由回退（决策 D3）。
 *
 * <p>Vue 前端挂在 {@code /ui/}（history 模式），深链/刷新 {@code /ui/console} 这类路径时浏览器直接向后端要该 URL，
 * 后端无对应静态文件会 404。本控制器把 {@code /ui/} 下**无扩展名**（非静态资源）的路径 forward 到 {@code /ui/index.html}，
 * 交给 vue-router 接管。带扩展名的（{@code /ui/assets/*.js} 等）不匹配此规则，仍由 Spring 静态资源处理器正常返回。
 *
 * <p>只影响 {@code /ui/**}：根 {@code /}（旧展示台 index.html 欢迎页）、Step1~18 端点、{@code /activity-marketing/**}
 * 一律不碰。前端产物由 {@code -Pfrontend} 构建拷进 {@code static/ui/}；未构建时本 forward 404（无害）。
 * 安全链：{@code /ui/**} 非 {@code /activity-marketing/**}，auth 档走链二 permitAll，无需额外放行。
 */
@Controller
public class SpaForwardController {

    /** {@code /ui/} 与 {@code /ui/{一段无点路径}} → index.html（首屏与一级路由）。 */
    @GetMapping({"/ui", "/ui/", "/ui/{path:[^.]*}"})
    public String root() {
        return "forward:/ui/index.html";
    }

    /** {@code /ui/a/b/c}（多级、各段无点）→ index.html（深层路由如 /ui/console/activities/123/edit）。 */
    @GetMapping("/ui/{path:[^.]*}/{subpath:[^.]*}/**")
    public String deep() {
        return "forward:/ui/index.html";
    }
}
