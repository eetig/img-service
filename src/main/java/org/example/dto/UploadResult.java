package org.example.dto;

public class UploadResult {

    private String url;
    private String fileName;
    private long size;

    public UploadResult() {
    }

    public UploadResult(String url, String fileName, long size) {
        this.url = url;
        this.fileName = fileName;
        this.size = size;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
