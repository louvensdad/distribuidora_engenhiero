package br.com.engenheiro.api.organization;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {
    private final BranchService service;

    public BranchController(BranchService service) {
        this.service = service;
    }

    public record BranchList(List<BranchService.BranchView> items) {
    }

    @GetMapping
    public ResponseEntity<BranchList> list(Actor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new BranchList(service.list(actor)));
    }

    @PostMapping
    public ResponseEntity<BranchService.BranchView> create(Actor actor, @RequestBody BranchService.CreateCommand body) {
        BranchService.BranchView v = service.create(actor, body);
        return ResponseEntity.created(URI.create("/api/v1/branches/" + v.id())).cacheControl(CacheControl.noStore()).body(v);
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<BranchService.BranchView> status(Actor actor, @PathVariable UUID id,
                                                           @RequestBody BranchService.StatusCommand body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.setStatus(actor, id, body));
    }
}
