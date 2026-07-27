package com.asm.delivery.exception;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A malformed request must not be reported as a server fault. Both of these used to fall through to
 * the catch-all and answer 500, inflating the 5xx rate and burying real failures among caller errors.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void malformedJsonBodyIsBadRequestNotServerError() {
        var ex = new HttpMessageNotReadableException("bad json",
                new com.fasterxml.jackson.core.JsonParseException(null, "Invalid UTF-8 middle byte 0x66"),
                new org.springframework.http.server.ServletServerHttpRequest(
                        new org.springframework.mock.web.MockHttpServletRequest()));

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleUnreadableBody(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getErrorCode()).isEqualTo("MALFORMED_REQUEST_BODY");
    }

    @Test
    void missingRequiredParameterIsBadRequestAndNamesTheParameter() throws Exception {
        MethodParameter param = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);
        var ex = new MissingServletRequestParameterException("target", param.getParameterType().getSimpleName());

        ResponseEntity<GlobalExceptionHandler.ErrorResponse> res = handler.handleMissingParam(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getErrorCode()).isEqualTo("MISSING_REQUEST_PARAMETER");
        assertThat(res.getBody().getMessage()).contains("target");
        assertThat(res.getBody().getErrorParams()).containsEntry("parameter", "target");
    }

    @SuppressWarnings("unused")
    private void sample(String target) { }
}
