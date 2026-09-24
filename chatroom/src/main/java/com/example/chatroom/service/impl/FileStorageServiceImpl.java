package com.example.chatroom.service.impl;

import com.example.chatroom.common.constant.Constant;
import com.example.chatroom.common.exception.UserException;
import com.example.chatroom.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘文件存储。
 * <p>
 * 存本地磁盘而不是存数据库 BLOB/base64：数据库只该存"路径"这种小字段，
 * 图片本身放文件系统，既省数据库体积又能直接用静态资源那套缓存。
 * 真上线的话把这里换成 OSS/S3 的实现即可，接口不用动。
 */
@Slf4j
@Service
public class FileStorageServiceImpl implements FileStorageService {

    /** 白名单。不要用黑名单挡 jsp/exe —— 漏一个就是一个漏洞，只放行已知安全的格式 */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private static final long MAX_SIZE = 5 * 1024 * 1024L;

    /** 上传根目录（绝对路径） */
    private final Path root;

    public FileStorageServiceImpl(@Value("${file.upload-dir:./upload}") String uploadDir) throws IOException {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("上传根目录：{}", root);
    }

    @Override
    public String saveImage(MultipartFile file, String subDir, Integer ownerId) {
        if (file == null || file.isEmpty()) {
            throw new UserException("请选择要上传的图片");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new UserException("图片太大了，最多 5MB");
        }

        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw new UserException("只支持 jpg / png / gif / webp 格式的图片");
        }

        // 关键：文件名完全由服务端生成，用户传上来的原始文件名一个字都不用。
        // 否则一个叫 "../../application.yml"（目录穿越）或者 "shell.jsp"（上传可执行脚本）
        // 的文件名就能直接搞事。
        String filename = ownerId + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                + "." + ext;

        Path dir = root.resolve(subDir).normalize();
        // 双保险：即使 subDir 被污染，解析出来的目录也必须还在根目录内
        if (!dir.startsWith(root)) {
            throw new UserException("非法的上传目录");
        }

        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename));
        } catch (IOException e) {
            log.error("保存上传文件失败：{}", e.getMessage(), e);
            throw new UserException("图片保存失败，请稍后重试");
        }

        String url = Constant.UPLOAD_URL_PREFIX + "/" + subDir + "/" + filename;
        log.info("文件已保存：{}", url);
        return url;
    }

    @Override
    public Resource loadAsResource(String url) {
        Path path = resolve(url);
        if (path == null || !Files.isReadable(path)) {
            return null;
        }
        return new FileSystemResource(path);
    }

    @Override
    public void deleteQuietly(String url) {
        Path path = resolve(url);
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // 删不掉就算了：留下一个孤儿文件，不影响功能，不该因此让"换头像"整个失败
            log.warn("删除旧文件失败：{}，原因 = {}", path, e.getMessage());
        }
    }

    /**
     * 把 /upload/xxx/yyy.png 这样的 URL 反解成磁盘路径。
     * 反解同样要做"必须还在根目录内"的校验 —— 数据库里的值也可能是脏的。
     */
    private Path resolve(String url) {
        if (!StringUtils.hasText(url) || !url.startsWith(Constant.UPLOAD_URL_PREFIX + "/")) {
            return null;
        }
        String relative = url.substring(Constant.UPLOAD_URL_PREFIX.length() + 1);
        Path path = root.resolve(relative).normalize();
        return path.startsWith(root) ? path : null;
    }

    private String extensionOf(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            return "";
        }
        int idx = originalFilename.lastIndexOf('.');
        if (idx < 0 || idx == originalFilename.length() - 1) {
            return "";
        }
        return originalFilename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
