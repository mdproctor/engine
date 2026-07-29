package io.casehub.engine.rest.exception;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatchAllExceptionMapperTest {

    private final CatchAllExceptionMapper mapper = new CatchAllExceptionMapper();

    @Test
    void webApplicationException_preservesStatusCode() {
        Response response = mapper.toResponse(new BadRequestException("bad input"));
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    void notFoundException_preserves404() {
        Response response = mapper.toResponse(new NotFoundException("missing"));
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    void genericRuntimeException_returns500() {
        Response response = mapper.toResponse(new IllegalStateException("unexpected"));
        assertThat(response.getStatus()).isEqualTo(500);
    }
}
