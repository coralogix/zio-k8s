# leader-example

To run with [minikube](https://minikube.sigs.k8s.io/docs/start/):

```shell
minikube start
eval $(minikube docker-env)
sbt leader-example/docker:publishLocal
kubectl apply -f  examples/leader-example/src/main/k8s/pod1.yaml
kubectl apply -f  examples/leader-example/src/main/k8s/pod2.yaml
```