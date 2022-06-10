public MyClass(def script) {
    throw new Exception("FOO=${script.env.FOO}")
}