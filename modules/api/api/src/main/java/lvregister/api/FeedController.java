package lvregister.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.Optional;
import lvregister.api.dto.Dtos;
import lvregister.api.dto.Dtos.EventDto;
import lvregister.api.dto.Dtos.MassAddressDto;
import lvregister.api.dto.Dtos.PageDto;
import lvregister.api.dto.Dtos.RunDto;
import lvregister.api.dto.Dtos.RunSummaryDto;
import lvregister.api.dto.Dtos.StatsDto;
import lvregister.serving.ServingNames;
import lvregister.serving.ServingQueries;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Changes feed, stats, pipeline runs and the value feature. */
@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "feed")
class FeedController {

    static final String EVENT_TYPE = "[A-Z_]{3,40}";

    private final ServingQueries queries;
    private final CachedQueries cached;

    FeedController(ServingQueries queries, CachedQueries cached) {
        this.queries = queries;
        this.cached = cached;
    }

    @GetMapping("/events")
    @Operation(summary = "All change events, newest capture first; optionally of one capture date and one type")
    PageDto<EventDto> events(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) Optional<LocalDate> date,
            @RequestParam Optional<@Pattern(regexp = EVENT_TYPE) String> type,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return Dtos.page(queries.events(date, type, page, size), Dtos::event);
    }

    @GetMapping("/stats")
    @Operation(summary = "Companies per legal form, status and region; events per capture")
    StatsDto stats() {
        return new StatsDto(cached.stats(ServingNames.COMPANIES_STAT).stream().map(Dtos::stat).toList(),
                cached.stats(ServingNames.EVENTS_STAT).stream().map(Dtos::stat).toList());
    }

    @GetMapping("/runs")
    @Operation(summary = "Pipeline runs, newest capture first")
    PageDto<RunSummaryDto> runs(@RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return Dtos.page(queries.runs(page, size), Dtos::runSummary);
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "A pipeline run with its quality report")
    RunDto run(@PathVariable @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String id) {
        return queries.run(id).map(Dtos::run).orElseThrow(() -> new NotFoundException("no run with id " + id));
    }

    @GetMapping("/features/mass-addresses")
    @Operation(summary = "Addresses with many active companies (mass registration), best rank first")
    PageDto<MassAddressDto> massAddresses(@RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return Dtos.page(queries.feature(ServingNames.MASS_ADDRESSES, page, size), Dtos::massAddress);
    }
}
