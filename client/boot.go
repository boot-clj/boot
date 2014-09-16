package main

import (
	"errors"
	"fmt"
	"github.com/jackpal/bencode-go"
	"net"
	"os"
	"strings"
)

type Request struct {
	Op   string "op"
	Code string "code"
}

type Response struct {
	Session string   "session"
	Out     string   "out"
	Status  []string "status"
	Ex      string   "ex"
	RootEx  string   "root-ex"
}

func message(args []string) (string, error) {
	conn, err := net.Dial("tcp", "0.0.0.0:53788")
	if err != nil {
		return "", err
	}
	var tasks string
	if len(args) > 0 {
		tasks = strings.Join(args, " ")
	} else {
		tasks = "help"
	}
  fmt.Print(tasks)
	if err := bencode.Marshal(conn, Request{"eval", "(boot " + tasks + ") nil"}); err != nil {
		return "", err
	}
	var res Response
	if err := bencode.Unmarshal(conn, &res); err != nil {
		return "", err
	}
	if res.Ex != "" {
		return "", errors.New(res.Ex)
	}
	return res.Out, nil
}

func main() {
	out, err := message(os.Args[1:])
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	} else {
		fmt.Fprint(os.Stdout, out)
		os.Exit(0)
	}
}
