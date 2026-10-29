package com.kholodilin.repogrowth.auth.api;

import com.kholodilin.repogrowth.auth.config.AuthProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@Profile("!cli")
public class LoginPageController {

    private final AuthProvider provider;

    public LoginPageController(AuthProvider provider) {
        this.provider = provider;
    }

    @GetMapping("/login")
    public String login(
            @RequestParam(value = "error", required = false) String error,
            Model model
    ) {
        model.addAttribute("mock", provider == AuthProvider.MOCK);
        model.addAttribute("oidcLoginUrl", "/oauth2/authorization/oidc");
        model.addAttribute("error", error);
        return "login";
    }
}
