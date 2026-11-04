"""Offline checks for SSM deployment preconditions and completion reporting."""
import unittest
from unittest.mock import patch

from scripts import github_deploy as deployment

INSTANCE = "i-0123456789abcdef0"
IMAGE = "123456789012.dkr.ecr.us-east-1.amazonaws.com/demo-backend@sha256:" + "a" * 64
COMMIT = "b" * 40


class DeploymentTests(unittest.TestCase):
    def test_untrusted_image_and_commit_are_rejected_before_aws(self):
        for image, commit in [(IMAGE + ";id", COMMIT), (IMAGE.replace("us-east-1", "eu-west-1"), COMMIT),
                              (IMAGE.replace("@sha256:" + "a" * 64, ":latest"), COMMIT), (IMAGE, "main")]:
            with self.subTest(image=image, commit=commit), patch.object(deployment, "aws_json") as aws:
                with self.assertRaises(deployment.DeployError):
                    deployment.deploy(INSTANCE, image, commit)
                aws.assert_not_called()

    def test_stopped_instance_is_not_started_or_sent_a_command(self):
        response = {"Reservations": [{"Instances": [{"InstanceId": INSTANCE, "State": {"Name": "stopped"}}]}]}
        with patch.object(deployment, "aws_json", return_value=response) as aws:
            with self.assertRaisesRegex(deployment.DeployError, "NOT deployed"):
                deployment.deploy(INSTANCE, IMAGE, COMMIT)
            self.assertEqual(1, aws.call_count)
            self.assertEqual(["ec2", "describe-instances"], aws.call_args.args[0][:2])

    def test_offline_ssm_is_rejected(self):
        responses = [
            {"Reservations": [{"Instances": [{"InstanceId": INSTANCE, "State": {"Name": "running"}}]}]},
            {"InstanceInformationList": [{"InstanceId": INSTANCE, "PingStatus": "ConnectionLost"}]},
        ]
        with patch.object(deployment, "aws_json", side_effect=responses) as aws:
            with self.assertRaisesRegex(deployment.DeployError, "NOT deployed"):
                deployment.deploy(INSTANCE, IMAGE, COMMIT)
            self.assertEqual(2, aws.call_count)

    def test_wrong_instance_response_is_rejected(self):
        response = {"Reservations": [{"Instances": [{"InstanceId": "i-00000000000000000", "State": {"Name": "running"}}]}]}
        with patch.object(deployment, "aws_json", return_value=response):
            with self.assertRaisesRegex(deployment.DeployError, "exactly"):
                deployment.require_online_instance(INSTANCE)

    def test_wrong_ssm_instance_is_rejected(self):
        responses = [
            {"Reservations": [{"Instances": [{"InstanceId": INSTANCE, "State": {"Name": "running"}}]}]},
            {"InstanceInformationList": [{"InstanceId": "i-00000000000000000", "PingStatus": "Online"}]},
        ]
        with patch.object(deployment, "aws_json", side_effect=responses):
            with self.assertRaisesRegex(deployment.DeployError, "NOT deployed"):
                deployment.require_online_instance(INSTANCE)

    def test_success_is_reported_only_after_remote_command_completes(self):
        responses = [
            {"Reservations": [{"Instances": [{"InstanceId": INSTANCE, "State": {"Name": "running"}}]}]},
            {"InstanceInformationList": [{"InstanceId": INSTANCE, "PingStatus": "Online"}]},
            {"Command": {"CommandId": "test-command"}},
            {"Status": "InProgress", "ResponseCode": -1},
            {"Status": "Success", "ResponseCode": 0},
        ]
        with patch.object(deployment, "aws_json", side_effect=responses) as aws, patch.object(deployment.time, "sleep"):
            self.assertEqual("test-command", deployment.deploy(INSTANCE, IMAGE, COMMIT))
            self.assertEqual(5, aws.call_count)

    def test_command_failure_is_not_reported_as_success(self):
        with patch.object(deployment, "aws_json", return_value={"Status": "Failed", "ResponseCode": 1}):
            with self.assertRaisesRegex(deployment.DeployError, "did not succeed"):
                deployment.wait_for_command("test-command", INSTANCE)

    def test_initial_invocation_not_found_is_retried(self):
        responses = [deployment.AwsCliError("ssm get-command-invocation", "error (InvocationDoesNotExist)"),
                     {"Status": "Success", "ResponseCode": 0}]
        with patch.object(deployment, "aws_json", side_effect=responses), patch.object(deployment.time, "sleep"):
            self.assertEqual("Success", deployment.wait_for_command("test", INSTANCE)["Status"])

    def test_unknown_completion_times_out_without_retrying_deployment(self):
        with patch.object(deployment.time, "monotonic", side_effect=[0, 1000]), patch.object(deployment, "aws_json") as aws:
            with self.assertRaisesRegex(deployment.DeployError, "not confirmed complete"):
                deployment.wait_for_command("test", INSTANCE, timeout=5)
            aws.assert_not_called()


if __name__ == "__main__":
    unittest.main()
