package br.com.engenheiro.api.team;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import br.com.engenheiro.api.shared.web.IfMatch;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Equipe › Funcionários: folgas e férias (calendário de disponibilidade) e desempenho por função (ADR-021). */
@RestController
@RequestMapping("/api/v1/team")
public class TeamController {
    private static final String IF_MATCH = "If-Match";
    private final AbsenceService absences;
    private final PerformanceService performance;

    public TeamController(AbsenceService absences, PerformanceService performance) {
        this.absences = absences;
        this.performance = performance;
    }

    @GetMapping("/calendar")
    public ResponseEntity<AbsenceService.Calendar> calendar(Actor actor, @RequestParam(required = false) LocalDate from,
                                                            @RequestParam(required = false) LocalDate to) {
        return ok(absences.calendar(actor, from, to));
    }

    @PostMapping("/absences")
    public ResponseEntity<AbsenceService.AbsenceView> create(Actor actor, @RequestBody AbsenceService.CreateCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(absences.create(actor, body));
    }

    @PostMapping("/absences/{id}/approve")
    public ResponseEntity<AbsenceService.AbsenceView> approve(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) AbsenceService.NoteCommand body) {
        return ok(absences.approve(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/absences/{id}/reject")
    public ResponseEntity<AbsenceService.AbsenceView> reject(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody AbsenceService.NoteCommand body) {
        return ok(absences.reject(actor, id, IfMatch.version(ifMatch), body));
    }

    @PostMapping("/absences/{id}/cancel")
    public ResponseEntity<AbsenceService.AbsenceView> cancel(Actor actor, @PathVariable UUID id,
            @RequestHeader(value = IF_MATCH, required = false) String ifMatch, @RequestBody AbsenceService.NoteCommand body) {
        return ok(absences.cancel(actor, id, IfMatch.version(ifMatch), body));
    }

    @GetMapping("/performance")
    public ResponseEntity<PerformanceService.Report> performance(Actor actor, @RequestParam(required = false) LocalDate from,
                                                                 @RequestParam(required = false) LocalDate to) {
        return ok(performance.report(actor, from, to));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
