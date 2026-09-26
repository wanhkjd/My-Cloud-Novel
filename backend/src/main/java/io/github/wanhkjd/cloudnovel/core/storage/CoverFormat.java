package io.github.wanhkjd.cloudnovel.core.storage;

import java.util.Optional;

/** 允许的封面图片格式。真实类型只由字节魔数判定，绝不信任客户端声明的 Content-Type， 以拒绝伪装成图片的 SVG/HTML 等可执行内容（安全要求）。 */
public enum CoverFormat {
    /** JPEG 图片。 */
    JPEG("jpg", "image/jpeg"),
    /** PNG 图片。 */
    PNG("png", "image/png"),
    /** WebP 图片。 */
    WEBP("webp", "image/webp");

    private final String extension;
    private final String contentType;

    CoverFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /**
     * 返回存储用的小写扩展名（不含点）。
     *
     * @return 扩展名，如 {@code jpg}
     */
    public String extension() {
        return extension;
    }

    /**
     * 返回下发时的 HTTP 内容类型。
     *
     * @return 内容类型，如 {@code image/jpeg}
     */
    public String contentType() {
        return contentType;
    }

    /**
     * 按前 12 字节魔数判定真实图片格式；无法判定或被拒类型返回空。
     *
     * @param bytes 待判定的原始字节，可为 null
     * @return 命中的格式，或 {@link Optional#empty()}
     */
    public static Optional<CoverFormat> detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return Optional.empty();
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if ((bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D
                && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A
                && (bytes[7] & 0xFF) == 0x0A) {
            return Optional.of(PNG);
        }
        if (bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /**
     * 按存储扩展名反查格式，用于读回时决定内容类型。
     *
     * @param extension 小写扩展名（不含点），可为 null
     * @return 命中的格式，或 {@link Optional#empty()}
     */
    public static Optional<CoverFormat> fromExtension(String extension) {
        for (CoverFormat format : values()) {
            if (format.extension.equals(extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
