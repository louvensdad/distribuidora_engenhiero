package br.com.engenheiro.api.central;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Central do patrão no site (F10): aprovações pendentes e busca geral, ambas filtradas por permissão no servidor. */
@RestController
@RequestMapping("/api/v1")
public class CentralController {
    private final ApprovalCenterService approvals;
    private final SearchService search;

    public CentralController(ApprovalCenterService approvals, SearchService search) {
        this.approvals = approvals;
        this.search = search;
    }

    @GetMapping("/approvals")
    public ResponseEntity<ApprovalCenterService.Center> approvals(Actor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(approvals.center(actor));
    }

    @GetMapping("/search")
    public ResponseEntity<SearchService.Result> search(Actor actor, @RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(search.search(actor, q));
    }
}
