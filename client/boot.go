package main

import (
	"fmt"
	"github.com/jackpal/bencode-go"
	"net"
	"os"
	"strconv"
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

func (req Request) send(conn net.Conn) (*Response, error) {
  if err := bencode.Marshal(conn, req); err != nil {
		return nil, err
	}
  res := &Response{}
	if err := bencode.Unmarshal(conn, res); err != nil {
		return nil, err
	}
	return res, nil
}

func NewRequest(taskargs []string) *Request {
	code := "(boot"
	if len(taskargs) > 0 {
		for _, taskarg := range taskargs {
			code += " " + strconv.Quote(taskarg)
		}
		code += ")"
	} else {
		code += " help"
	}
	return &Request{"eval", code}
}

func main() {
	conn, err := net.Dial("tcp", "0.0.0.0:52644")
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error connecting to boot build server: %v\n", err)
		os.Exit(1)
	}
	req := NewRequest(os.Args[1:])
	res, err := req.send(conn)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error encoding or decoding tasks: %v\n", err)
		os.Exit(1)
	} else if res.Ex != "" {
		fmt.Fprintf(os.Stderr, "Error from boot build server: %v\n", res.Ex)
		os.Exit(1)
	}
	fmt.Print(res.Out)
}
