package com.kholodilin.repogrowth.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;

class SpaControllerTest {
  @Test
  void spaReturnsIndexOrNotFoundDependingOnClasspathResource() {
    var response = new SpaController().spa(mock(HttpServletRequest.class));
    if (new ClassPathResource("static/index.html").exists()) {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).isNotNull();
    } else {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }
}
