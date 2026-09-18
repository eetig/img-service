package org.example.controller;

import org.example.dto.Result;
import org.example.dto.UploadResult;
import org.example.service.ImgService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/img")
public class ImgController {

    private final ImgService imgService;

    public ImgController(ImgService imgService) {
        this.imgService = imgService;
    }

    @PostMapping("/upload")
    public Result<UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(imgService.upload(file));
    }

    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam("fileName") String fileName) {
        imgService.delete(fileName);
        return Result.success(null);
    }

    @GetMapping("/getPreviewUrl")
    public Result<String> getPreviewUrl(@RequestParam("fileName") String fileName) {
        return Result.success(imgService.getPreviewUrl(fileName));
    }
}
