package br.com.engenheiro.api.ai;

import br.com.engenheiro.api.identityaccess.domain.Actor;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F15: IA da gestão, a mesma para o site e o app Patrão. Só o servidor fala com o provedor. */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final AiService ai;

    public AiController(AiService ai) {
        this.ai = ai;
    }

    @GetMapping("/status")
    public ResponseEntity<AiService.Status> status(Actor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ai.status(actor));
    }

    @PostMapping("/questions")
    public ResponseEntity<AiService.QuestionView> ask(Actor actor, @RequestBody AiService.AskCommand body) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ai.ask(actor, body));
    }

    @GetMapping("/questions")
    public ResponseEntity<AiService.Page> mine(Actor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ai.mine(actor));
    }

    @GetMapping("/questions/{id}")
    public ResponseEntity<AiService.QuestionView> get(Actor actor, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ai.get(actor, id));
    }
}
