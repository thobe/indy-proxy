Java Dynamic Proxy using InvokeDynamic
======================================

An alternative to `java.lang.reflect.Proxy` that uses [InvokeDynamic][indy] to implement methods.
This has the benefit of only executing the lookup logic once for each method, not for each invocation.

[indy]: http://docs.oracle.com/javase/7/docs/technotes/guides/vm/multiple-language-support.html#invokedynamic