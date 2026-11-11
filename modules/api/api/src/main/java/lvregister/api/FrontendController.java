package lvregister.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The frontend's routes are client-side (React Router), so a reload or a shared link to
 * {@code /companies/40003000001} must return the app's {@code index.html}. Static files and
 * {@code /api/**} are served as usual. Only present in the production image (web/dist).
 */
@Controller
class FrontendController {

    @GetMapping({"/search", "/changes", "/insights", "/pipeline", "/pipeline/{id}", "/companies/{reg}",
            "/companies/{reg}/network"})
    String app() {
        return "forward:/index.html";
    }
}
