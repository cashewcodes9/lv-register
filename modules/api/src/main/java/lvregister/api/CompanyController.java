package lvregister.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lvregister.api.dto.Dtos;
import lvregister.api.dto.Dtos.CompanyDto;
import lvregister.api.dto.Dtos.EventDto;
import lvregister.api.dto.Dtos.NetworkDto;
import lvregister.api.dto.Dtos.PageDto;
import lvregister.api.dto.Dtos.SearchHitDto;
import lvregister.serving.NetworkBuilder;
import lvregister.serving.ServingQueries;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Company search, company page, timeline and network. */
@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "companies")
class CompanyController {

    static final String REG_NUMBER = "\\d{9}|\\d{11}";

    private final ServingQueries queries;
    private final CachedQueries cached;
    private final NetworkBuilder network;

    CompanyController(ServingQueries queries, CachedQueries cached, NetworkBuilder network) {
        this.queries = queries;
        this.cached = cached;
        this.network = network;
    }

    @GetMapping("/search")
    @Operation(summary = "Search companies by name (case, diacritics and small typos ignored) or registration number")
    PageDto<SearchHitDto> search(@RequestParam @NotBlank @Size(min = 2, max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return Dtos.page(cached.search(q.strip(), page, size), Dtos::hit);
    }

    @GetMapping("/companies/{reg}")
    @Operation(summary = "A company with its officers, owners and beneficial owners")
    CompanyDto company(@PathVariable @Pattern(regexp = REG_NUMBER) String reg) {
        return queries.company(reg).map(Dtos::company).orElseThrow(() -> unknown(reg));
    }

    @GetMapping("/companies/{reg}/events")
    @Operation(summary = "Change events of a company, newest capture first")
    PageDto<EventDto> events(@PathVariable @Pattern(regexp = REG_NUMBER) String reg,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        if (queries.company(reg).isEmpty()) {
            throw unknown(reg);
        }
        return Dtos.page(queries.eventsOf(reg, page, size), Dtos::event);
    }

    @GetMapping("/companies/{reg}/network")
    @Operation(summary = "Company–person graph: the company, its parties, their other companies and the parties of those")
    NetworkDto network(@PathVariable @Pattern(regexp = REG_NUMBER) String reg) {
        return network.build(reg).map(Dtos::network).orElseThrow(() -> unknown(reg));
    }

    private static NotFoundException unknown(String reg) {
        return new NotFoundException("no company with registration number " + reg);
    }
}
