def print_debug_output(service){
    sh(script: """
           echo "#####Printing logs for debugging#####"
           kubectl describe pods -n elk -l app=${service}
           kubectl logs -n elk -l app=${service}
           exit 1
           """
        )
}

def pod_readiness(service){
    boolean success = false
    counter = 0
    while (!success) {
        try{
            if(counter<30){
                ready_pods = sh(returnStdout: true, script: """kubectl get pods --sort-by=.status.startTime -n elk -l app=${service} | tail -n 1 | awk \'{print \$2}\'| cut -d \'/\' -f1""").trim()
                if(ready_pods=='1'){
                    success = true
                }
                else{
                    counter = counter + 1
                    sleep(30)
                }
            }
            else{
                throw new Exception("Taking too long")
            }
        }
        catch(Exception e){
            print_debug_output(service)
        }
    }
}

def statefulset_status(service,total_pods) {
    boolean success = false
    counter = 0
    while (!success) {
        try{
            if(counter<30){
                ready_pods = sh(returnStdout: true, script: """kubectl get sts -n elk -l app=${service} | grep ${service} | awk \'{ print \$2 }\' | cut -d\'/\' -f 1""").trim()
                println(ready_pods)
                if(ready_pods==total_pods){
                    success = true
                }
                else{
                    counter = counter + 1
                    sleep(30)
                }
            }
            else{
                throw new Exception("Taking too long")
            }
        }
        catch(Exception e){
            print_debug_output(service)
        }
    }
}


def get_pod_name(service){
    return sh(returnStdout: true, script: """kubectl get pod --sort-by=.status.startTime -n elk -l app=${service} | tail -n 1 | cut -d \' \' -f1""").trim()
}

def elasticsearch_testing(){
    statefulset_status("elasticsearch","3")
    sh "kubectl cp test_data/elasticsearch/elastic_test_data elasticsearch-0:/tmp/elastic_test_data -n elk"
    sh 'kubectl exec elasticsearch-0 -n elk -- curl -s -H \"Content-Type: application/x-ndjson\" -XPOST localhost:9200/_bulk --data-binary \"@/tmp/elastic_test_data\"; echo > /tmp/output_data'
    try{
        sh( script: """
            echo "#### Testing elastic search data ####"
            echo "#### Below is the test data #####"
            cat test_data/elasticsearch/elastic_test_data
            kubectl cp test_data/elasticsearch/elastic_test_data elasticsearch-0:/tmp/elastic_test_data -n elk
            kubectl exec elasticsearch-0 -n elk -- curl -s -H \"Content-Type: application/x-ndjson\" -XPOST localhost:9200/_bulk --data-binary \"@/tmp/elastic_test_data\"; echo
            kubectl exec elasticsearch-0 -n elk -- curl -s -X GET \"localhost:9200/loading_data/_search?pretty\" | jq \".hits.hits\" | tee /tmp/elastic_output
            diff test_data/elasticsearch/elastic_test_output /tmp/elastic_output
            """
        )
    }
    catch(Exception e){
        print_debug_output("elasticsearch")
    }
}

def logstash_testing(){
    pod_readiness("logstash")
    pod_name = get_pod_name("logstash")
    try{
        sh (script: """
            echo "#### Testing logstash api #####"
            kubectl exec ${pod_name} -n elk -- curl http://localhost:9600/
            """
        )
    }
    catch(Exception e){
        print_debug_output("elasticsearch")
    }
}

def kibana_testing(){
    pod_readiness("kibana")
    pod_name = get_pod_name("kibana")
    try{
        sh(script: """
            echo "#### Testing Kibana port #####"
            kubectl exec ${pod_name} -n elk -- curl http://localhost:5601/
            """
        )
    }
    catch(Exception e){
        print_debug_output("kibana")
    }
}

def filebeat_testing(){
    pod_readiness("filebeat")
    pod_name = get_pod_name("filebeat")
    try{
        sh(script: """
            echo "#### Testing filebeat configuration #####"
            kubectl exec ${pod_name} -n elk -- filebeat test output -c /etc/filebeat.yml
            """
        )
    }
    catch(Exception e){
        print_debug_output("filebeat")
    }
}
node('jenkins-slave') {
    try {
        stage('Preparation') {
            git 'https://github.com/vivekreddy94/elk_test.git'
        }
        stage("Test node") {
            sh(script: """
            echo "####check if docker is installed###"
            docker version
            echo "####Check if kubernetes is installed###"
            kubectl version
            echo "####Check if polaris is installed####"
            which polaris
            echo "#####Check if kubeval is installed####"
            which kubeval
            """
            )
        }
        stage("Setup node") {
            withCredentials([file(credentialsId: 'kubeconfig', variable: 'configfile')]) {
                sh(script: """
                echo "####Setup kubeconfig file####"
                rm -rf ~/.kube;mkdir ~/.kube;cp ${configfile} ~/.kube/config
                echo "####Setup kubernetes yaml files for kubernetes code analysis####"
                ansible-playbook kubernetes-linting.yml -i inventories/stage
                """
                )
            }
        }

        stage("Validate kubernetes code"){
            sh( script: """
                echo "#### Perform kubeval to check validity####"
                kubeval /tmp/*/final*.yml
                echo "#### Perform polaris scan to check any missing setting #####"
                ls -l
                polaris audit --config kube-code-analysis/config_elastisearch.yml --audit-path /tmp/elasticsearch/final-elastic.yml --set-exit-code-below-score 100
                polaris audit --config kube-code-analysis/config_kibana.yml --audit-path  /tmp/kibana/final-kibana.yml --set-exit-code-below-score 100
                polaris audit --config kube-code-analysis/config_filebeat.yml --audit-path /tmp/filebeat/final-filebeat.yml --set-exit-code-below-score 100
                polaris audit --config kube-code-analysis/config_logstash.yml --audit-path /tmp/logstash/final-logstash.yml --set-exit-code-below-score 100
                """
            )
        }

        stage("Deploy and test elasticsearch") {
            sh "ansible-playbook elasticsearch.yml -i inventories/stage"
            sleep(30)
            elasticsearch_testing()
        }

        stage("Deploy and test logstash") {
            sh "ansible-playbook logstash.yml -i inventories/stage"
            sleep(30)
            logstash_testing()
        }
        stage("Deploy and test filebeat") {
            sh "ansible-playbook filebeat.yml -i inventories/stage"
            sleep(30)
            filebeat_testing()
        }
        stage("Deploy and test kibana") {
            sh "ansible-playbook kibana.yml -i inventories/stage"
            sleep(30)
            kibana_testing()
        }
        stage('Load data and test') {
            sh "kubectl run loggenerator-1 -n default --restart=Never --image chentex/random-logger:latest 0 1 10000"
            sh "kubectl run loggenerator-2 -n default --restart=Never --image chentex/random-logger:latest 0 1 10000"
            sleep(120)
            sh "kubectl delete pods --field-selector=status.phase==Succeeded -n default"
            echo "##### Testing all components after loading some data #####"
            elasticsearch_testing()
            logstash_testing()
            filebeat_testing()
            kibana_testing()
        }
    /*
        stage('cleanup stage') {
            sh "ansible-playbook kibana.yml -i inventories/stage --extra-vars \"install_action=absent\""
            sh "ansible-playbook logstash.yml -i inventories/stage --extra-vars \"install_action=absent\""
            sh "ansible-playbook filebeat.yml -i inventories/stage --extra-vars \"install_action=absent\""
        }
        stage("Deploy to prod") {
            sh "ansible-playbook elk_stack.yml -i inventories/production"
        } */
    }
    catch (e) {
        throw e
    }
    finally {
        sh 'rm -rf ~/.kube/config'
    }
}


