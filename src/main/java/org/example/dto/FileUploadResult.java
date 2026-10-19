package org.example.dto;

/**
 * 资料归档的上传结果（变更-126）。
 *
 * <p>与 {@link UploadResult} 的区别：那个给的是**图片 URL**（可以直接 &lt;img&gt; 显示）；
 * 资料是 PDF/Word/Excel，浏览器不能直接显示，所以这里回**够用的元数据**，
 * 由 hnd_factory 存进 {@code archive_document}，下载时再按 {@code fileName} 取。
 */
public class FileUploadResult {

    /** 对象名（不含 archive/ 前缀）：<32位hex>.<扩展名>，由本服务生成 */
    private String fileName;
    /** 上传时的原文件名，供下载时还原（只作元数据，永不参与对象名拼接） */
    private String origName;
    private long size;
    private String contentType;

    public FileUploadResult() {
    }

    public FileUploadResult(String fileName, String origName, long size, String contentType) {
        this.fileName = fileName;
        this.origName = origName;
        this.size = size;
        this.contentType = contentType;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getOrigName() {
        return origName;
    }

    public void setOrigName(String origName) {
        this.origName = origName;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
}
