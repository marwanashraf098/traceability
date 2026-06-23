package com.traceability.inventory;

import com.traceability.identity.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/blocklist")
public class BlocklistController {

    private final BlocklistService svc;

    public BlocklistController(BlocklistService svc) {
        this.svc = svc;
    }

    record AddBody(String phone, String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public List<BlocklistService.BlocklistEntry> list() {
        return svc.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public BlocklistService.BlocklistEntry add(
            @RequestBody AddBody body,
            @AuthenticationPrincipal CustomUserDetails principal) {
        return svc.add(body.phone(), body.reason(), principal.userId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER', 'MANAGER')")
    public void remove(@PathVariable UUID id,
                       @AuthenticationPrincipal CustomUserDetails principal) {
        svc.remove(id, principal.userId());
    }
}
