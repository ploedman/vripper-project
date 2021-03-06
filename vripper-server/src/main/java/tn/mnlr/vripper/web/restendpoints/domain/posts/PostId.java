package tn.mnlr.vripper.web.restendpoints.domain.posts;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@ToString
public class PostId {
    private String postId;

    public PostId(String postId) {
        this.postId = postId;
    }
}