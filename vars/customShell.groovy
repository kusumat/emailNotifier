def call(command) {
    sh '#!/bin/sh -e\n' + command
}