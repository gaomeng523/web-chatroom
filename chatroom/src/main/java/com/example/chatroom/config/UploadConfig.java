package com.example.chatroom.config;

import com.example.chatroom.common.constant.Constant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 把本地上传目录映射成静态资源，让 /upload/xxx.png 能直接访问。
 * <p>
 * 为什么还需要它：头像和聊天图片存的是磁盘文件，
 * 但浏览器要能通过 URL 拿到它们，就得有个 URL -> 磁盘目录的映射。
 */
@Configuration
public class UploadConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public UploadConfig(@Value("${file.upload-dir:./upload}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 必须用 file: 前缀明确指出这是文件系统路径，
        // 否则 Spring 会当成 classpath 资源去找，结果永远是 404。
        // 结尾的 / 也不能省，少了它拼接出来的路径会缺一层目录。
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler(Constant.UPLOAD_URL_PREFIX + "/**")
                .addResourceLocations(location);
    }
}
