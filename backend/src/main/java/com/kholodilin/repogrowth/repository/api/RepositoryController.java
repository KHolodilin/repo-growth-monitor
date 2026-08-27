package com.kholodilin.repogrowth.repository.api;

import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.Repository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryService repositoryService;

    public RepositoryController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    @GetMapping
    public List<RepositoryResponse> list(@RequestParam(defaultValue = "false") boolean refresh) {
        return repositoryService.list(refresh).stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable long id) {
        return toResponse(repositoryService.get(id));
    }

    @PostMapping("/{id}/tracking")
    public RepositoryResponse tracking(@PathVariable long id, @Valid @RequestBody TrackingRequest request) {
        return toResponse(repositoryService.setTracking(id, request.enabled()));
    }

    private RepositoryResponse toResponse(Repository repository) {
        GitHubOwner owner = repositoryService.owner(repository.ownerId());
        return new RepositoryResponse(
                repository.id(),
                repository.githubId(),
                repository.name(),
                repository.fullName(),
                repository.description(),
                repository.visibility(),
                repository.defaultBranch(),
                repository.language(),
                repository.fork(),
                repository.archived(),
                repository.stars(),
                repository.forks(),
                repository.openIssues(),
                repository.trackingEnabled(),
                repository.githubCreatedAt(),
                repository.githubUpdatedAt(),
                new RepositoryResponse.OwnerResponse(
                        owner.id(),
                        owner.githubId(),
                        owner.login(),
                        owner.ownerType().name(),
                        owner.avatarUrl(),
                        owner.htmlUrl()
                )
        );
    }
}
