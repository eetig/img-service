package org.example.exception;

import org.example.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常出口。
 *
 * <p>口径（2026-10-15 code-review 修正）：**对外只回固定文案，内部细节只进日志**。
 * 本服务没有引入 spring-boot-starter-security，`/api/img/**` 是免鉴权端点，
 * 把 `e.getMessage()` 拼进响应等于把 MinIO 错误码、内网 endpoint、桶名对象名、
 * AWT/ImageIO 的解析细节免费送给调用方，省掉攻击者一轮探测。
 *
 * <p>{@link BusinessException} 是例外：它的文案由本服务自己撰写（面向用户的那几句），
 * 因此照原样返回 —— 但**抛它的时候不许拼接第三方异常文本**（各处的 `catch` 已按此改正）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        return Result.error(400, e.getMessage());
    }

    /**
     * multipart 大小超限。
     *
     * <p>文案里**不写具体数字**：上限由 `spring.servlet.multipart.max-file-size` 决定，
     * 而写死的「最大 5MB」曾与底图接口实际允许的 10MB 冲突，把作者和用户一起带偏。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传超过 multipart 上限：{}", e.getMessage());
        return Result.error(413, "上传文件超过大小限制，请压缩后再传");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未预期的异常", e);
        return Result.error(500, "服务器内部错误，请稍后重试");
    }
}
