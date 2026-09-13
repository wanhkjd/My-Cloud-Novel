package io.github.wanhkjd.cloudnovel.exception;

/** 可预期的业务错误；不依赖 HTTP，状态码由控制器异常处理器决定。 */
public final class BusinessException extends RuntimeException {
    /** 序列化版本。 */
    private static final long serialVersionUID = 1L;

    /** 业务错误种类。 */
    private final Kind kind;

    /** 业务错误分类，不携带 Web 层状态码。 */
    public enum Kind {
        /** 资源不存在或调用者不可见。 */
        NOT_FOUND,
        /** 唯一性冲突或不可复用的会话编号。 */
        CONFLICT
    }

    private BusinessException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * 创建不可见资源错误。
     *
     * @param message 可安全展示给用户的信息
     * @return 业务异常
     */
    public static BusinessException notFound(String message) {
        return new BusinessException(Kind.NOT_FOUND, message);
    }

    /**
     * 创建业务冲突错误。
     *
     * @param message 可安全展示给用户的信息
     * @return 业务异常
     */
    public static BusinessException conflict(String message) {
        return new BusinessException(Kind.CONFLICT, message);
    }

    /**
     * 获取业务错误分类。
     *
     * @return 与传输协议无关的错误种类
     */
    public Kind getKind() {
        return kind;
    }
}
