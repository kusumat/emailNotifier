def displayEnv(script) {
    println "Shared lib var/lib FOO=${FOO}"
    MyClass c = new MyClass(script);
}