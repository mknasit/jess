package ann;

import java.lang.annotation.*;

@Repeatable(Tag.Container.class)
public @interface Tag {
    String value() default "";
    @interface Container {
        Tag[] value();
    }
}
