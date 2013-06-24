;;; hesokuri -*- lexical-binding: t; coding: utf-8 -*-
;;; Personal distributed backup system

(require 'cl-lib)

(defvar hesokuri-sources '()
  "Defines the locations from which to read hesokuri repos. It is a list of zero
or more SOURCE items, where a SOURCE item is in this form:
 (NAME
  (\"/foo/bar/path1/\"
   MAC-ADDRESS-USING-PATH-1-A
   MAC-ADDRESS-USING-PATH-1-B
   ...)
  (\"/home/jdoe/path2/\"
   MAC-ADDRESS-USING-PATH-2-A
   MAC-ADDRESS-USING-PATH-2-B
   ...)
  ...)
NAME is a lisp symbol identifying the source in status and error messages. After
name follows one or more path settings, each path settings a list which contains
a path followed by one or more MAC addresses. Each MAC address has a copy of the
repo at the given path. Here is an example value for this variable for a
hypothetical user with two Mac OS machines and a Linux machine:

\((filing-cabinet
  (\"/Users/jdoe/filing-cabinet\"
   3c075562313f
   12f390d51bfd))
 (emacs-d
  (\"/Users/jdoe/.emacs.d\"
   3c075562313f
   12f390d51bfd)
  (\"/home/jdoe/.emacs.d\"
   4ce77b30233d))
 (hesokuri
  (\"/Users/matvore/hesokuri\"
   3c075562313f
   12f390d51bfd)
  (\"/home/jdoe/hesokuri\"
   4ce77b30233d)))

Notice that the Mac OS machines use the same directory structure, so the
directories only have to appear once in the list.
")

(defun hesokuri-macs-in-buffer ()
  "Returns a list of MAC addresses in the current buffer. The MAC address is
returned as a symbol in the form ffffffffffff."
  (let ((i -1)
        (s (buffer-string))
        res)
    (while (setq i (string-match "[0-9a-fA-F].?:..?:..?:..?:..?:..? " s (+ i 2)))
      (let* ((raw-mac (downcase (substring s i (1- (match-end 0)))))
             (two-digits-each
              (mapcar (lambda (s) (if (eql 1 (length s)) (concat "0" s) s))
                      (split-string raw-mac ":"))))
        (push (intern (apply 'concat two-digits-each)) res)))
    res))

(defun hesokuri-mac-of (ip)
  "Given an IP address as a string, attempts to find the MAC address associated
with it. The MAC address is returned as a symbol in the form ffffffffffff.
Returns NIL if there was an error."
  (with-temp-buffer
    (let ((arp-result (call-process "arp" nil t nil ip)))
      (unless (eql 0 arp-result)
        (error "Error running arp for ip address %s (exitcode %s): %s"
               ip arp-result (buffer-string)))
      (let* ((arp-out-macs (hesokuri-macs-in-buffer))
             (found-count (length arp-out-macs)))
        (unless (eql found-count 1)
          (error "Expected 1 MAC address in arp output, but found %d: %s"
                 found-count (buffer-string)))
        (car arp-out-macs)))))

(defun hesokuri-source-ids ()
  "Returns a list of all the known source IDs configured. The IDs are read from
the hesokuri-sources special variable."
  (mapcar 'car hesokuri-sources))

(defun hesokuri-sources-on-machine (mac)
  "Returns a list of sources on the machine identified with the given MAC
address. List is in the form of:
\((source-id1 path-string1) (source-id2 path-string2) ...)
Each path string indicates the location of the source on the machine specified
by MAC."
  (let (res)
    (dolist (source hesokuri-sources res)
      (dolist (path-spec (cdr source))
        (when (cl-find mac path-spec)
          (push `(,(car source) ,(car path-spec)) res))))))

(defun -hesokuri-push (source-id peer-repo remote-branch)
  (insert (format "Pushing %s to %s\n" source-id peer-repo))
  (call-process "git" nil t t "push" peer-repo 
                (concat "master:" remote-branch)))

(defun -hesokuri-pull (source-id peer-repo)
  (insert (format "Pulling %s from %s\n" source-id peer-repo))
  (call-process "git" nil t t "pull" peer-repo "master"))

(defun -hesokuri-clone (source-id local-path peer-repo)
  (when (file-exists-p local-path)
    (error "Cannot clone to a path that already exists: %s" local-path))
  (insert (format "Cloning %s from %s to %s\n"
                  source-id peer-repo local-path))
  (call-process "git" nil t t "clone" peer-repo local-path))

(defmacro -hesokuri-report (func-name &rest pass-args)
  `(let* ((pass-args (list . ,pass-args))
          (all-args (cons ,func-name pass-args)))
     (if (eq 0 (apply ,func-name pass-args))
         (prog1 t (push all-args succeeded))
       (prog1 nil (push all-args failed)))))

(defun hesokuri-kuri (peer-ip)
  "Kuris, or syncs, all sources shared between this machine and the one with the
IP address specified in the string PEER-IP."
  (interactive "MIP address to sync with: ")
  (let* ((peer-mac (hesokuri-mac-of peer-ip))
         (peer-sources (hesokuri-sources-on-machine peer-mac))
         (local-macs
          (with-temp-buffer
            (call-process "ifconfig" nil t nil "-a")
            (hesokuri-macs-in-buffer))))
    (unless local-macs
      (error "Could not figure out local MAC address from 'ifconfig -a'"))
    (unless peer-sources
      (error "Could not find any sources on %s (MAC %s)" peer-ip peer-mac))
    (pop-to-buffer (get-buffer-create "*hesokuri*"))
    (goto-char (point-max))
    (insert "\n\nkuri operation at "
            (format-time-string "%c" (current-time))
            ":\n")
    (dolist (local-mac local-macs)
      (let ((local-sources (hesokuri-sources-on-machine local-mac))
            (remote-track-name (concat (symbol-name local-mac) "-master"))
            failed succeeded)
        (dolist (local-source local-sources)
          (let* ((source-id (car local-source))
                 (local-path (cadr local-source))
                 (peer-source (assq source-id peer-sources))
                 (peer-path (cadr peer-source))
                 (peer-repo (format "ssh://%s%s" peer-ip peer-path)))
            (cond
             ((not peer-source))
             ((not (file-exists-p local-path))
              (-hesokuri-report '-hesokuri-clone
                                source-id local-path peer-repo))
             (t
              (cd local-path)
              (or (-hesokuri-report '-hesokuri-push source-id
                                    peer-repo "master")
                  (-hesokuri-report '-hesokuri-push source-id
                                    peer-repo remote-track-name)
              (-hesokuri-report '-hesokuri-pull source-id peer-repo))))))
        (when succeeded
          (insert "\nThe following operations succeeded:\n")
          (dolist (s succeeded)
            (insert (prin1-to-string s) "\n")))
        (when failed
          (insert "\nThe following operations failed:\n")
          (dolist (f failed)
            (insert (prin1-to-string f) "\n")))))))
