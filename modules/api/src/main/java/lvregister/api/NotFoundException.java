package lvregister.api;

/** A resource that does not exist: 404 as ProblemDetail. */
class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NotFoundException(String message) {
        super(message);
    }
}
