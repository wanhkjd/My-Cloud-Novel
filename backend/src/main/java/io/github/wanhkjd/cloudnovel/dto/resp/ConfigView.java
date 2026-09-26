package io.github.wanhkjd.cloudnovel.dto.resp;

/**
 * 前端渲染上传约束所需的公开配置；只暴露体积上限，不泄露任何路径或凭据。
 *
 * @param maxUploadBytes 单个 TXT 上传的字节上限，与 {@code spring.servlet.multipart.max-file-size} 一致
 */
public record ConfigView(long maxUploadBytes) {}
