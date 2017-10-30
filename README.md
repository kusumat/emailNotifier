_kony-common pipeline shared library for Jenkins pipeline jobs_
=============================================================
**kony-common** is a pipeline shared library which contains logic for building application binaries, test automation scripts binaries, managing test runs on [AWS DeviceFarm service](https://aws.amazon.com/ru/device-farm/).

Library been build based on [Pipeline Shared Groovy Libraries Plugin](https://wiki.jenkins.io/display/JENKINS/Pipeline+Shared+Groovy+Libraries+Plugin) and its ability to extend (see [Extending with Shared Libraries](https://jenkins.io/doc/book/pipeline/shared-libraries/) for more details).

By default, library defined on global level (_Manage Jenkins » Configure System » Global Pipeline Libraries_), this also means, that all scripts in library are treated as **trusted**.

More details about this library and its usage could be found at [Jenkins functional flow](https://konysolutions.atlassian.net/wiki/spaces/APPFACT/pages/147541815/Jenkins+functional+flow).