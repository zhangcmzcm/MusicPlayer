package org.example.music.email.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.music.common.web.ClientIpResolver;
import org.example.music.email.dto.EmailSendCodeRequest;
import org.example.music.email.dto.EmailSendCodeResponse;
import org.example.music.email.service.EmailSendCodeService;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/email")
@Tag(name = "Email", description = "通用邮箱服务接口")
public class EmailController {

    private final EmailSendCodeService emailSendCodeService;
    private final ClientIpResolver clientIpResolver;

    public EmailController(
            EmailSendCodeService emailSendCodeService,
            ClientIpResolver clientIpResolver
    ) {
        this.emailSendCodeService = emailSendCodeService;
        this.clientIpResolver = clientIpResolver;
    }

    @PostMapping("/send-code")
    @Operation(summary = "发送邮箱验证码", description = "同步生成并发送邮箱验证码；当前仅支持注册用途")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "验证码发送成功",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = EmailSendCodeResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "请求校验失败",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "发送频率超限",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "邮件发送失败",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "验证码存储不可用",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "服务器内部错误",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    public ResponseEntity<EmailSendCodeResponse> sendCode(
            @Valid @RequestBody EmailSendCodeRequest request,
            HttpServletRequest httpServletRequest
    ) {
        String clientIp = clientIpResolver.resolve(httpServletRequest);
        return ResponseEntity.ok(emailSendCodeService.send(request, clientIp));
    }
}