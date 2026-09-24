package com.example.chatroom.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    /**
     * 保存一张上传的图片，返回可直接访问的相对 URL。
     *
     * @param file    上传的文件
     * @param subDir  子目录，见 Constant.AVATAR_DIR / CHAT_IMAGE_DIR
     * @param ownerId 用来拼文件名前缀（自己的 id），不同用户的文件不会同名
     */
    String saveImage(MultipartFile file, String subDir, Integer ownerId);

    /**
     * 把数据库里存的 URL 反查成可读的资源，找不到返回 null
     */
    Resource loadAsResource(String url);

    /**
     * 删除一个之前保存的文件（换头像时清掉旧图）。尽力而为，失败只记日志不抛异常。
     */
    void deleteQuietly(String url);
}
